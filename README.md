# Ryzen VPN for Android

A native Kotlin app with three locations, one Connect or Disconnect button, and a
status indicator. The official WireGuard tunnel library uses Android's
`VpnService` to route packets into an encrypted UDP tunnel. Each VPS forwards
IPv4 traffic to the internet using its public IP. No API server is required.

## Build and install

1. Install Android Studio with Android SDK 35 and JDK 17. Open this folder as an
   existing project. Use Gradle 8.9 with Android Gradle Plugin 8.7.3. This source
   archive uses a locally installed Gradle distribution.
2. Run `gradle :app:assembleDebug` from this folder. With a USB-debugging-enabled
   Android phone attached, run `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
   The app requires Android 8.0 or newer.
3. In the app select the country, tap **Import profile for selected location**,
   choose its configuration file, and tap **Connect**. Accept Android's VPN
   prompt. Disconnect before selecting another location.

The app keeps one profile per slot in private, backup-excluded app storage.
Import only profiles you created. The country label describes the slot you chose
and the app relies on you to match it to the VPS. WireGuard authenticates the
peer by public key. Keep exported profiles and private keys out of GitHub and
delete extra copies after import.

## Set up each VPS

Choose a small Ubuntu 24.04 LTS KVM instance with a public IPv4 address. Start
with one vCPU and 1 GB RAM where available. Compare the total including IPv4
fees and outbound transfer. Check that the selected region is in the intended
country and that the plan permits VPN use and inbound UDP.

| Location | Provider to check | Tunnel IPv4 | Phone IPv6 |
| --- | --- | --- | --- |
| Türkiye | [HOSTKEY Istanbul](https://hostkey.com/vps/turkey/) | 10.77.1.1/24 | fd77:1::2/128 |
| Brazil | [Vultr São Paulo](https://blogs.vultr.com/Ol-Brasil-Vultrs-20th-Cloud-Location-is-in-So-Paulo) | 10.77.2.1/24 | fd77:2::2/128 |
| Ukraine | [Deltahost Ukraine](https://deltahost.com/vps/vps-ua.html) | 10.77.3.1/24 | fd77:3::2/128 |

1. Configure SSH key access and keep the system updated. In the cloud firewall,
   permit UDP 51820 and restrict SSH to your admin IP where practical. If a host
   firewall is active, allow the UDP port and the WireGuard forwarding rules there
   too. Keep an open SSH session while changing firewall rules.
2. Install WireGuard and generate a unique server key on each VPS.

   ```bash
   sudo apt update
   sudo apt install wireguard iptables
   sudo sh -c 'umask 077; wg genkey > /etc/wireguard/server.key; wg pubkey < /etc/wireguard/server.key > /etc/wireguard/server.pub'
   printf '%s\n' 'net.ipv4.ip_forward=1' | sudo tee /etc/sysctl.d/90-wireguard.conf
   sudo sysctl --system
   ip -4 route get 1.1.1.1
   ```

3. On a trusted computer with WireGuard tools, generate a different phone keypair
   per region. Copy only the phone public key to the VPS.

   ```bash
   umask 077
   wg genkey > tr-phone.key
   wg pubkey < tr-phone.key > tr-phone.pub
   ```

4. On the VPS, create `/etc/wireguard/wg0.conf` as root with mode 600.
   The example is for Türkiye. Replace `WAN_INTERFACE` with the device after
   `dev` in the route command, such as `ens3`. Substitute the actual key values.

   ```ini
   [Interface]
   Address = 10.77.1.1/24
   ListenPort = 51820
   PrivateKey = SERVER_PRIVATE_KEY
   PostUp = iptables -I FORWARD -i %i -o WAN_INTERFACE -s 10.77.1.2/32 -j ACCEPT; iptables -I FORWARD -i WAN_INTERFACE -o %i -d 10.77.1.2/32 -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT; iptables -t nat -A POSTROUTING -s 10.77.1.2/32 -o WAN_INTERFACE -j MASQUERADE
   PostDown = iptables -D FORWARD -i %i -o WAN_INTERFACE -s 10.77.1.2/32 -j ACCEPT; iptables -D FORWARD -i WAN_INTERFACE -o %i -d 10.77.1.2/32 -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT; iptables -t nat -D POSTROUTING -s 10.77.1.2/32 -o WAN_INTERFACE -j MASQUERADE

   [Peer]
   PublicKey = PHONE_PUBLIC_KEY
   AllowedIPs = 10.77.1.2/32
   ```

   ```bash
   sudo chmod 600 /etc/wireguard/wg0.conf
   sudo systemctl enable --now wg-quick@wg0
   sudo wg show wg0
   ```

5. Create `tr.conf` on your trusted computer. Transfer it to Android over USB
   and import it into the Türkiye slot.

   ```ini
   [Interface]
   PrivateKey = PHONE_PRIVATE_KEY
   Address = 10.77.1.2/32, fd77:1::2/128
   DNS = 1.1.1.1

   [Peer]
   PublicKey = SERVER_PUBLIC_KEY
   Endpoint = VPS_PUBLIC_IPV4:51820
   AllowedIPs = 0.0.0.0/0, ::/0
   PersistentKeepalive = 25
   ```

   The client captures both address families. The VPS peer only accepts the
   phone's IPv4 source address, so IPv6 internet access is intentionally blocked
   while the tunnel is active. This keeps the server setup IPv4-only.

6. Repeat for Brazil and Ukraine, replacing the tunnel subnet and phone IPv6
   address as listed above. Use independent keys and the appropriate public IP.
   Use `br.conf` and `ua.conf` for the corresponding slots.

## Verify on your phone

- Open a website after tapping Connect. Run `sudo wg show wg0` on that VPS and
  confirm a recent handshake and increasing transfer counters. **Connected**
  in the app means Android brought up the local tunnel interface.
- Check that your public IPv4 matches the selected VPS. Check DNS resolution and
  confirm that the phone's ISP IPv6 address is absent while the VPN is active.
- Disconnect and confirm normal networking returns. Repeat on Wi-Fi and mobile
  data for every country. Traffic uses the ordinary connection after disconnect
  or if Android removes the VPN. Always-on mode is disabled in this starter.
- If there is no handshake, check the endpoint, UDP firewall, server service,
  and both public keys. If there is a handshake but websites fail, check
  forwarding, the NAT interface, and DNS.

Core UI and connection code are in
`app/src/main/java/com/personal/privatevpn/MainActivity.kt`. The VPN service is
provided by the WireGuard dependency and merged into the app manifest.

## Globe interface and server availability

Version 0.2 adds a draggable globe, animated country focus, greyed-out unsupported
countries, city profiles, flags, host addresses and a circular power button.
Tap a highlighted country or use the country selector. Add a city and import its
working WireGuard configuration. Existing single-country profiles migrate to
an Imported location entry. The app does not infer geographic location from IP
addresses, so enter the actual city and country of the server.

The pulse appears only while the selected peer has a handshake within the last
180 seconds. Speed values are measured tunnel traffic in Mbps, not maximum server
capacity. An idle connection can show 0 Mbps. A recent handshake authenticates
the peer but does not independently verify internet forwarding.

No hosted servers are included. No free source with verified WireGuard endpoints
in all three target countries has been established. Use your own servers or
legitimate working configurations. Each country shows an empty state until a
profile is imported.

## Validation status

The 0.1 APK compiled and passed Android lint. The 0.2 release workflow additionally
runs an Android 35 emulator launch check before publication. Physical-device VPN
connectivity requires working server configurations and remains unverified.
Debug builds from different runner instances may have different signing keys.
If Android rejects an update due to a signature mismatch, export or retain your
original profiles, uninstall the old app, install the new APK and import them
again. Uninstalling removes app data.
