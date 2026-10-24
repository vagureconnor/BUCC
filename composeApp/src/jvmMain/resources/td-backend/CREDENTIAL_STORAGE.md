# Health Cloud credential storage

On macOS, `AutoTDBackend` stores Health Cloud credentials in its application-private `health-cloud-vault` directory. Passwords are encrypted with AES-256-GCM; the local key is a separate 32-byte file. The directory is mode `0700`, and key and credential files are mode `0600`. The username is hashed for the filename and authenticated as encryption associated data.

The macOS backend does not call Keychain APIs or inspect, migrate, or delete old Keychain entries or legacy `credentials.json` files. Users whose Health Cloud password existed only in the old Keychain must enter it again in the app. Windows continues to use Windows Credential Vault.
