# VibeSync Google Drive database backup

The Profile → Chats & Cloud Backup card signs the user into Google and requests the Drive `appDataFolder` scope. The app uploads a local Room SQLite snapshot into the private Drive AppData area; the file name starts with `vibesync_room_db_`.

## Encryption and restore

Backups use AES-256-GCM. A random per-file salt and nonce are stored in the encrypted container header; the encryption key is derived from the user-entered passphrase with PBKDF2-HMAC-SHA256. The passphrase is not uploaded to Drive. On the current device, the app may cache it encrypted with Android Keystore for scheduled WorkManager backups. On another device the user must enter the original passphrase again.

**Keep this passphrase safe.** If it is lost, VibeSync cannot decrypt the backup. Restoring replaces matching local Room tables with the backup's contents, so create a fresh backup first if you need to preserve the destination device's current data.

## Google Cloud setup required

Enable Google Drive API in the Firebase/Google Cloud project used by this app. Configure the Android OAuth client for the app's package name and SHA fingerprints, and publish or allow the relevant Google Sign-In consent configuration. The repository already contains the Google Sign-In/Drive client helpers and uses the `DRIVE_APPDATA` scope; no service-account credential or Drive token is embedded in the Android source by this change.

## Scope and format

The upload contains the actual `dating_app.db` SQLite file after a WAL checkpoint, not only exported chat JSON. During restore, compatible user tables are copied into the open Room database while preserving the Room metadata tables and active DAO observers. Tables that do not exist in both database versions are skipped for compatibility. Test backups and restores with the same app version first when practical.
