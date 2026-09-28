package com.genzo.app.network

/**
 * Change this to point at your relay. Defaults to the Android emulator's
 * alias for "the host machine's localhost" — i.e. run
 * `RELAY_ADDR=127.0.0.1:8765 cargo run -p relay` from the Rust vertical
 * slice on your dev machine, then the default here reaches it from an
 * emulator with no changes needed.
 *
 * For real phones (not emulators) talking over the real internet, deploy
 * the relay somewhere reachable (see /docs/GET_IT_RUNNING.md for a
 * no-command-line path using Render.com) and set baseUrl to that
 * https:// URL here before building — a deployed relay gets a real
 * TLS certificate automatically, so no network_security_config change is
 * needed for that case (only the local-dev cleartext exception below
 * needs one).
 *
 * Testing two Android devices against each other (not just two emulators
 * on the same host) means both need a route to wherever the relay actually
 * runs — e.g. both on the same Wi-Fi as a laptop running the relay, using
 * that laptop's LAN IP here instead of 10.0.2.2, and adding that specific
 * host to res/xml/network_security_config.xml (see its comments).
 */
object RelayConfig {
    var baseUrl: String = "https://genzo-msg-app.onrender.com"
}
