# Activity Calendar for Android

A calendar view of your Fitbit activity. The app reads steps, distance, calories, floors, resting heart rate, sleep and workouts from Health Connect, and you can still enter or change any day by hand. Each day turns green when you meet your goals and red when you don't.

## Install on a phone

1. On the phone, open this repository's **Releases** page and download the newest `ActivityCalendar-<number>.apk`.
2. Open the downloaded file. If Android asks, allow your browser to **install unknown apps**, then tap **Install**.
3. Open **Activity Calendar** and tap **Connect**, then allow every permission Health Connect lists.

To update, tap **Update** when the app says a new version is ready, or use **Goals → Check for updates**. Android then asks you to confirm the install. Your data is kept.

## Get Fitbit data into Health Connect

In the Google Health app, turn on sharing with Health Connect and allow all categories. In Health Connect's settings, put Google Health first under **Data and access → Steps**, so the phone's own step counter doesn't override the watch.

## Good to know

- **Data stays on the phone.** Nothing is sent anywhere. Uninstalling the app deletes its data, so use **Import → Copy backup** now and then.
- **Health Connect only shares the last 30 days** from before you first connect. Older history can come in through a Google Takeout import.
- **Active Zone Minutes** isn't shared through Health Connect, so enter it by hand if you use that goal.
- **Manual changes are kept.** A value you edit isn't overwritten by later syncs. **Use synced data** on that day undoes your changes.

## Building

GitHub Actions builds the app on every push to `main` and publishes the APK as a release, which the app's Update button installs. `app/shared.keystore` signs every build with the same key, so updates install over the old version. The key file is AES-256 encrypted; its password is stored only in the repository's `KEYSTORE_PASSWORD` Actions secret.
