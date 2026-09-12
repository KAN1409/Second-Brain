# Khoj Mobile (Unofficial Android Client)

A lightweight Android shell for the official Khoj web app at `https://app.khoj.dev`.

## Features
- Persistent WebView cookies/session
- File upload picker
- Microphone and camera permission bridge for Khoj features
- Downloads to Android Downloads
- Handles `app.khoj.dev` deep links, including magic-login links
- External links open in the system browser
- No analytics or extra tracking added by this wrapper

## Important
This is an **unofficial** client and is not published or endorsed by the Khoj maintainers. It does not bundle the Khoj server or Khoj source code; it loads the official Khoj web application. Khoj itself is open source under AGPL-3.0: https://github.com/khoj-ai/khoj

Google OAuth may reject embedded WebViews. If that happens, use Khoj's email/magic-link login; `app.khoj.dev` links are routed back into this app.

## Build
`gradle :app:assembleDebug`

Package: `com.kareem.khojmobile`
