# Cloudflare Access login look

The app's outside address, `light-show.tronkowski.org`, sits behind Cloudflare Access. On the Zero Trust Free plan, the login page only takes a name, a logo, header and footer text, and a background colour. These settings apply to every Access app on the account, so live-admin and the Remote Falcon control panel get the same look.

Set them in **Zero Trust → Reusable components → Custom pages → Access login page → Manage**:

| Field | Value |
|---|---|
| Organization name | `Light Show Controller` |
| Logo URL | `https://raw.githubusercontent.com/ketronkowski/light-show-controller/main/docs/cloudflare/logo.png` |
| Header text | `Tronkowski Holiday Lights` |
| Footer text | `Show admins only` |
| Background color | `#0a0d12` (the app's background) |

The logo is served from this public repo, not from the app, because Access loads it before you've signed in, and the app is behind Access.

![logo](logo.png)

If the account ever moves to Pay-as-you-go, Access can use a full HTML template per app instead. That template could use the app's own fonts and an amber glow. It can't use JavaScript, so any animation would have to be done in CSS.
