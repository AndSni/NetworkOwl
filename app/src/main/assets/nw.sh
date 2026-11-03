#!/bin/sh
# NetWatch router helper for ASUS stock firmware (BusyBox ash).
# Lives in /tmp/netwatch (RAM only): a router reboot removes it and every rule it added.
#
#   nw.sh version          print script version
#   nw.sh snapshot [ping]  ensure counters, print one live sample (optionally with a 1-packet ping)
#   nw.sh clients          print the full client database (names, vendors)
#   nw.sh start [secs]     start the background recorder (default every 30 s)
#   nw.sh stop             stop the recorder
#   nw.sh status           recorder state and history size
#   nw.sh history [since]  print recorded samples newer than epoch <since>
#   nw.sh remove           stop the recorder and delete all NetWatch rules
#   nw.sh reboot [keep]    restart the router cleanly; "keep" first saves the history to /jffs
#                          (gzipped), and the next start/history call puts it back in RAM
#
# Counting rules sit in their own chain in the mangle table, so they never touch
# the filter table (parental controls, blocked devices). Every rule ends in RETURN
# and only counts; no packet is ever changed or dropped.

NW_VERSION=6
DIR=/tmp/netwatch
CHAIN=NETWATCH
LAN=br0
HIST=$DIR/history.log
SAVED=/jffs/netwatch_history.gz
PIDF=$DIR/recorder.pid
MAX_LINES=5760          # 48 h at 30 s
PING_TARGET=1.1.1.1

wan_if() {
	ip route 2>/dev/null | awk '$1 == "default" { for (i = 1; i < NF; i++) if ($i == "dev") { print $(i + 1); exit } }'
}

# IPs of LAN devices: ARP table on the LAN bridge (catches static IPs) + DHCP leases.
lan_ips() {
	{
		awk -v lan="$LAN" 'NR > 1 && $3 == "0x2" && $6 == lan { print $1 }' /proc/net/arp
		awk '{ print $3 }' /var/lib/misc/dnsmasq.leases 2>/dev/null
	} | sort | awk '!seen[$0]++'
}

# The app's live polling and the recorder both call this; the lock stops them adding the same rule twice.
ensure() {
	exec 9> $DIR/ensure.lock
	flock -x 9
	ensure_locked
	rc=$?
	flock -u 9
	return $rc
}

ensure_locked() {
	WAN=$(wan_if)
	[ -n "$WAN" ] || return 1
	iptables -t mangle -N $CHAIN 2>/dev/null
	iptables -t mangle -C FORWARD -j $CHAIN 2>/dev/null || iptables -t mangle -I FORWARD 1 -j $CHAIN
	have=$(iptables -t mangle -S $CHAIN)
	# The WAN interface changed (e.g. modem re-enumerated): start the chain over.
	case "$have" in
		*"-A $CHAIN"*) case "$have" in *"-o $WAN "*) ;; *) iptables -t mangle -F $CHAIN; have="" ;; esac ;;
	esac
	for ip in $(lan_ips); do
		case "$have" in
			*"-s $ip/32 "*) ;;
			*) iptables -t mangle -A $CHAIN -s "$ip" -i $LAN -o "$WAN" -j RETURN ;;
		esac
		case "$have" in
			*"-d $ip/32 "*) ;;
			*) iptables -t mangle -A $CHAIN -d "$ip" -i "$WAN" -o $LAN -j RETURN ;;
		esac
	done
}

# D <ip> <upload bytes> <download bytes>
counters() {
	iptables -t mangle -L $CHAIN -n -v -x 2>/dev/null | awk -v lan="$LAN" '
		$3 == "RETURN" {
			if ($6 == lan) up[$8] += $2
			else if ($7 == lan) down[$9] += $2
			ips[$6 == lan ? $8 : $9] = 1
		}
		END { for (ip in ips) printf "D %s %s %s\n", ip, up[ip] + 0, down[ip] + 0 }'
}

# C <ip> <tracked connections>
connections() {
	awk '{ for (i = 1; i <= NF; i++) if ($i ~ /^src=/) { c[substr($i, 5)]++; break } }
		END { for (ip in c) printf "C %s %d\n", ip, c[ip] }' /proc/net/nf_conntrack 2>/dev/null
}

# W <interface> <rx bytes> <tx bytes>
wan_bytes() {
	awk -v w="$WAN" '{ sub(/^ +/, "") } index($0, w ":") == 1 { sub(/^[^:]*:/, ""); split($0, f, " "); printf "W %s %s %s\n", w, f[1], f[9] }' /proc/net/dev
}

lte() {
	nvram show 2>/dev/null | grep -E '^usb_modem_act_(rsrp|rsrq|sinr|rssi|signal|band|pcc|scc|operation|provider|cellid)=' | sed 's/^usb_modem_act_/L /'
}

# P <loss %> <avg ms>
probe() {
	ping -c "${1:-3}" -W "${2:-2}" $PING_TARGET 2>/dev/null | awk '
		/packet loss/ { for (i = 1; i <= NF; i++) if ($i ~ /%$/) { loss = $i; sub(/%/, "", loss) } }
		/min\/avg\/max/ { split($0, a, "="); split(a[2], b, "/"); avg = b[2] + 0 }
		END { if (loss == "") loss = 100; printf "P %s %s\n", loss, (avg == "" ? -1 : avg) }'
}

snapshot() {
	ensure
	echo "V $NW_VERSION"
	echo "T $(date +%s) $(cut -d' ' -f1 /proc/uptime)"
	wan_bytes
	[ "$1" = ping ] && probe 1 1
	counters
	connections
	lte
	awk -v lan="$LAN" 'NR > 1 && $3 == "0x2" && $6 == lan { print "A", $1, toupper($4) }' /proc/net/arp
	awk '{ print "H", $2, $3, $4 }' /var/lib/misc/dnsmasq.leases 2>/dev/null
	echo "N $(nvram get custom_clientlist)"
	if [ -f /tmp/nmp_cache.js ]; then echo "J $(tr -d '\n' < /tmp/nmp_cache.js)"; fi
	if [ -f $PIDF ] && kill -0 "$(cat $PIDF)" 2>/dev/null; then echo "R 1"; else echo "R 0"; fi
}

clients() {
	if [ -f /jffs/nmp_cl_json.js ]; then echo "K $(tr -d '\n' < /jffs/nmp_cl_json.js)"; fi
}

# One history line: ts|wan rx|wan tx|rsrp|rsrq|sinr|band|loss|avg ms|ip:up:down:conns,...
record_once() {
	ensure || return
	{
		date +%s
		wan_bytes
		lte
		probe
		counters
		connections
	} | awk '
		NR == 1 { ts = $1; next }
		$1 == "W" { rx = $3; tx = $4 }
		$1 == "L" { split($2, kv, "="); v = substr($0, index($0, "=") + 1); l[kv[1]] = v }
		$1 == "P" { loss = $2; avg = $3 }
		$1 == "D" { up[$2] = $3; down[$2] = $4; ips[$2] = 1 }
		$1 == "C" { conns[$2] = $3 }
		END {
			devs = ""; sep = ""
			for (ip in ips) { devs = devs sep ip ":" up[ip] ":" down[ip] ":" (conns[ip] + 0); sep = "," }
			gsub(/\|/, "/", l["band"])
			printf "%s|%s|%s|%s|%s|%s|%s|%s|%s|%s\n", ts, rx, tx, l["rsrp"], l["rsrq"], l["sinr"], l["band"], loss, avg, devs
		}' >> $HIST
	n=$(wc -l < $HIST)
	if [ "$n" -gt $((MAX_LINES + 200)) ]; then
		tail -n $MAX_LINES $HIST > $HIST.tmp && mv $HIST.tmp $HIST
	fi
}

# History saved before a reboot goes back in front of anything recorded since, then leaves flash.
restore_saved() {
	[ -f $SAVED ] || return
	if gunzip -c $SAVED > $HIST.restore 2>/dev/null; then
		[ -f $HIST ] && cat $HIST >> $HIST.restore
		mv $HIST.restore $HIST
	fi
	rm -f $SAVED $HIST.restore
}

recorder() {
	interval=${1:-30}
	echo $$ > $PIDF
	while :; do
		record_once
		sleep "$interval"
	done
}

mkdir -p $DIR
case "$1" in
	version) echo "$NW_VERSION" ;;
	snapshot) snapshot "$2" ;;
	clients) clients ;;
	start)
		restore_saved
		if [ -f $PIDF ] && kill -0 "$(cat $PIDF)" 2>/dev/null; then
			echo "running $(cat $PIDF)"
		else
			nohup sh "$0" _recorder "${2:-30}" > /dev/null 2>&1 &
			sleep 1
			echo "started $(cat $PIDF 2>/dev/null)"
		fi
		;;
	_recorder) recorder "$2" ;;
	stop)
		[ -f $PIDF ] && kill "$(cat $PIDF)" 2>/dev/null
		rm -f $PIDF
		echo stopped
		;;
	status)
		if [ -f $PIDF ] && kill -0 "$(cat $PIDF)" 2>/dev/null; then r=1; else r=0; fi
		lines=0; first=0
		if [ -f $HIST ]; then lines=$(wc -l < $HIST); first=$(head -n 1 $HIST | cut -d'|' -f1); fi
		echo "S $r $lines $first"
		;;
	history)
		restore_saved
		[ -f $HIST ] && awk -F'|' -v s="${2:-0}" '$1 > s' $HIST
		;;
	reboot)
		[ -f $PIDF ] && kill "$(cat $PIDF)" 2>/dev/null
		rm -f $PIDF
		if [ "$2" = keep ] && [ -s $HIST ]; then gzip -c $HIST > $SAVED; fi
		# Detached and delayed, so this SSH command returns before the router goes down.
		nohup sh -c 'sleep 2; service reboot || reboot' > /dev/null 2>&1 &
		echo rebooting
		;;
	remove)
		[ -f $PIDF ] && kill "$(cat $PIDF)" 2>/dev/null
		rm -f $PIDF $HIST $SAVED
		while iptables -t mangle -D FORWARD -j $CHAIN 2>/dev/null; do :; done
		iptables -t mangle -F $CHAIN 2>/dev/null
		iptables -t mangle -X $CHAIN 2>/dev/null
		echo removed
		;;
	*) echo "usage: nw.sh version|snapshot|clients|start [secs]|stop|status|history [since]|remove|reboot [keep]" >&2; exit 1 ;;
esac
