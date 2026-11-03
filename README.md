# NetWatch

Android app that shows what is happening on a home network behind an ASUS router on **stock
firmware**: which devices are connected, what each one is downloading and uploading right now,
the LTE signal, and the internet ping. It also keeps a 48-hour history on the router itself.

Built for an ASUS 4G-AX56 (MediaTek MT7621, LTE WAN, firmware 3.0.0.4.382_45718). Other
ASUSWRT routers with SSH should mostly work, but only this one has been tested.

## How it works

The app logs in to the router over SSH (LAN only) and uploads a small shell script,
[`nw.sh`](app/src/main/assets/nw.sh), to `/tmp/netwatch` (RAM). The script:

- adds **counting-only** firewall rules: one chain, `NETWATCH`, in the `mangle` table, two
  `RETURN` rules per LAN device. They never change or drop a packet, and they stay out of
  the `filter` table where parental controls and blocked devices live;
- reads device names from the router's own client list (`/tmp/nmp_cache.js`,
  `/jffs/nmp_cl_json.js`, nvram `custom_clientlist`, DHCP leases);
- reads LTE signal values (`usb_modem_act_*` in nvram) and the connection table;
- optionally runs a **recorder** in the background: one sample every 30 s (traffic per device,
  LTE signal, ping to 1.1.1.1), kept for 48 h.

Nothing is written to the router's flash or settings. A reboot removes the script, the
rules and the history. **Setup → Remove from router** does the same without a reboot.

Accuracy check against a 30 MB download and 5 MB upload: the WAN total matched the device
counters to 99.99 %. The router has no hardware NAT on the USB modem path, so every packet
goes through the counters.

## Setup

1. Router web UI → **Administration → System**: Enable SSH **LAN only**, port 22,
   Allow Password Login **No**.
2. Open NetWatch → **Setup**, tap **Copy key**, and paste the key as a new line in the
   router's **Authorized Keys** box. Click **Apply**.
3. In NetWatch, enter the router address and admin user name, then tap **Save & test**.

The phone's key is an ECDSA P-256 key in the Android Keystore. It can sign but can't be
exported. The router's identity is pinned on first connect, and the app refuses to connect
if it changes.

## Screens

- **Live**: internet speed, ping, Wi-Fi→router latency, LTE SINR/RSRP/RSRQ with grades,
  bands and cell, then every device sorted by current traffic, with flags for new devices,
  weak Wi-Fi, many connections and heavy upload. Tap a device for its graph and details.
  **Keep monitoring in background** starts a foreground service that keeps polling.
- **History**: the router recorder's data for the last 1/6/24/48 h. Download/upload, ping
  and SINR charts, with slow periods (ping > 150 ms or packet loss) shaded red. Tap a
  chart to see which devices were busy at that moment.
- **Setup**: connection, key, update rate, recorder control, clean-up.

## Notes

- Devices behind a range extender in repeater mode share the extender's MAC in the ARP
  table, so they're identified by their DHCP lease instead. The router can't see their Wi-Fi
  signal, so the app shows them as "via extender".
- `/proc/net/dev` counters are 32-bit on this router and wrap at 4 GiB; the app handles that.
- LAN-side traffic (device to device) isn't counted, only traffic to and from the internet.

## Build and test

```sh
export JAVA_HOME=~/.gradle/jdks/eclipse_adoptium-21-amd64-linux.2
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest

# Opt-in integration tests: local sshd + the real router
NETWATCH_IT=1 NETWATCH_ROUTER=192.168.50.1 NETWATCH_ROUTER_USER=<user> \
NETWATCH_ROUTER_KEY=$HOME/.ssh/asus_router_ed25519 \
./gradlew :app:testDebugUnitTest --tests '*SshIntegrationTest*' --rerun
```
