# Photo Review

Photo Review is an Android app for reviewing photos selected by a separate photo server. It keeps the ten most recent selections, updates as new photos arrive, and lets you send feedback from a phone-sized interface.

## Features

- Displays up to ten recently selected photos, newest first.
- Loads recent history and listens for new selections in real time; reconnects and refreshes history if the event stream is interrupted.
- Shows each photo's path, album and available capture metadata.
- Submit a one-to-five-star rating, reject a photo, or report an EXIF tag as faulty.
- Automatically discovers a local `_photo-server._tcp` service, or lets you save a manual server address in **Server settings**.

## Requirements

- Android Studio with the Android SDK platform and build tools required by this project (`compileSdk` 37).
- An Android emulator or a phone running Android 8.0 (API 26) or newer.
- The Photo Review server running and reachable over HTTP or HTTPS. The app does not start or bundle the server.

## Connect to the server

The server API documentation is available at `http://localhost:8080/docs` when the server is running with its default address. The app uses the server's photo history and event stream, image URLs, and feedback endpoints.

By default, the app searches the local network for the DNS-SD service `_photo-server._tcp` in the `local` domain and connects to its advertised host and port. The discovered address is not saved, so it can change when the service or network changes. The server must advertise this service and expose the Photo Review HTTP API at that endpoint.

A saved manual address takes precedence over discovery. For a physical phone, `localhost` refers to the phone itself, not the computer hosting the server:

1. Make sure the phone and server computer can reach each other on the network.
2. In Photo Review, open **Server settings** and enter the computer's LAN address, for example `http://192.168.1.10:8080`.
3. Save the address. It is persisted for future launches.

Choose **Use automatic discovery** in **Server settings** to remove the saved address and return to service discovery. Choose **Discover again** there to restart a search. On the standard Android emulator, a manual address such as `http://10.0.2.2:8080` can be used to reach a server on the development computer if the service is not visible through local discovery.

For a physical phone, the server must listen on a network interface the phone can reach, and the computer's firewall must allow its port. Use the actual LAN address and port for your network. An empty photo list is expected until the server has selected photos; check the connection status to distinguish that from a server connection problem.

On Android 17 (API 37) and newer, Photo Review requests the **local network access** permission before discovering or connecting to servers on your Wi-Fi or other local network. Grant it when prompted, or use the permission notice in the app to retry or open app settings if it was denied. Public internet servers remain usable without this permission.

## Build and run

Open the project in Android Studio and let Gradle sync, or use the Gradle wrapper from the project root:

```shell
./gradlew assembleDebug
```

To install on a connected device or running emulator:

```shell
./gradlew installDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Use the app

When connected, Photo Review shows the most recent selections at the top. New selections appear automatically. If you have scrolled down to older photos, **New photos available** appears when a newer selection arrives; tap it to return to the newest photo.

For each photo, choose a rating from 1 to 5, tap **Reject**, or enter an EXIF tag name and tap **Report tag**. Feedback is sent to the server, and any server or network error is shown in the app.

## Run tests

Run the local unit tests and build the debug app:

```shell
./gradlew testDebugUnitTest assembleDebug
```

Run the Compose instrumentation tests as well (requires a connected device or running emulator):

```shell
./gradlew connectedDebugAndroidTest
```

## Troubleshooting

- **The app stays on “Searching for photo server”**: confirm the server advertises `_photo-server._tcp` in the local domain, grant local network access where requested, and check that the phone and server can reach each other. Use **Discover again** to restart the search or save a manual address in **Server settings**.
- **The app stays on “Connecting to photo server”**: verify the advertised endpoint or the saved address and port, and confirm the device can reach that host. For an emulator, use `http://10.0.2.2:8080` to reach a server on the development computer.
- **Connected, but no photos are listed**: the server may not have selected any photos yet. The app displays history and new selections; it does not initiate photo selection itself.
- **Images do not load on a phone**: confirm the server-provided image URLs are reachable from the phone and use the same accessible host as the configured server.
- **Phone cannot connect over Wi-Fi**: use the computer's LAN IP instead of `localhost`, and check server bind settings, Wi-Fi isolation and firewall rules.
- **Local server cannot connect on Android 17 or newer**: grant Photo Review's local network access permission; if you previously denied it, use the app's notice to retry or open app settings.

## Network security

The app currently allows cleartext HTTP to support the requested local server setup. HTTP traffic is unencrypted; use it only on a trusted network. Prefer HTTPS when the server and deployment support it.