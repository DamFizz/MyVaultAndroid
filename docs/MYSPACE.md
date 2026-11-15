# My Space: bank accounts and payment QR codes

My Space is the second tab of MyVault. It keeps the details people often ask you for: your bank
account numbers and your payment QR codes. You can copy or share them in a few taps without
opening your banking app.

MyVault is an organiser, not a banking app. It never connects to a bank, never makes payments, and
never asks for online-banking logins.

## What you can do

- **Save bank accounts**
  - Fields: bank (Malaysian list or a custom name), nickname, account holder, account number,
    DuitNow ID, account type, notes, colour and favourite.
  - You can save several accounts from the same bank.
  - An account can have a number, a QR, or both.
- **Save a payment QR on its own**, for example a DuitNow QR with no account number.
- **Import a QR** with the Android Photo Picker. MyVault sees only the photo you choose and asks
  for no storage permission.
  - **QR check:** MyVault checks whether a QR code can be read from the image and warns you if
    not.
  - **Crop to QR:** trims the image to the code with a white margin.
  - **Replace or remove** the QR at any time.
- **View the QR**
  - Always on white, with no glass, tint or blur over the code.
  - Tap it to open full screen. Screen brightness goes up while it is shown, to help scanners.
- **Copy**
  - Copying an account number copies the exact original digits, leading zeros included.
  - The clipboard copy is marked sensitive and cleared after the time set in Settings, but only if
    it has not been replaced by something newer since.
- **Share** in three modes. Each mode shows a preview and opens the Android Sharesheet; you pick
  the app and the person.
  1. **Number:** text with the account number. Including the bank name and the account holder is
     optional.
  2. **QR:** the saved QR image itself, not a link or file path.
  3. **Both:** either *Image + text* (the QR image with the text attached) or *Payment card*. The
     payment card is one PNG with the bank, holder, exact number and the original QR. Use it with
     apps that drop the text from an image.
- **Search, favourites and recent:** search matches names and account digits, favourites sit at
  the top, and recently used entries appear as quick chips.
- **Long-press a card** for Copy number, Share number, Share QR, Share both and Edit.

## Storage, sync and backup

- **Records.** Each entry is an encrypted vault record of kind `space`, and each QR image is its
  own encrypted record of kind `asset` (the original bytes, base64, inside the ciphertext). Both
  use the vault's record key and AAD binding, exactly like password items.
- **One write.** An entry and its new QR are written together, so the entry never points at an
  image that was not saved. A replaced or removed image becomes a tombstone in that same write.
- **Sync (Google Drive).** Entries and their QR images sync to every device. If an entry is
  edited on two devices at the same time, the newest version wins and the other is kept as a
  "conflict copy", the same rule as items.
- **Backups.** Backups made on Android include entries and QR images. Restore-merge adds missing
  ones, skips identical ones, and adds differing versions as "restored copy".
- **The web app** can open and sync a vault that contains these records; the compatibility test
  checks this. It does not show them yet.
  - Backups made from the web app currently leave My Space out.
  - Backups made on Android include it.
- **Image size.**
  - Originals up to 1.5 MB are kept byte-for-byte.
  - Larger images are scaled to at most 2000 px and stored as PNG, or as JPEG at quality 92 if
    the PNG would still be too large.
  - Files over 25 MB are refused.

## Privacy

- Nothing is decrypted to disk except when you tap Share.
  - The copy goes to the app's private cache (`cache/share/<random>/`).
  - It is offered through a non-exported FileProvider with a one-time read grant.
  - It is never put in your gallery.
- **Clean-up of share copies.**
  - Share copies older than 10 minutes are deleted whenever you share again.
  - When the vault locks, copies older than 2 minutes are deleted; newer ones are kept for a moment
    because the receiving app may still be reading them.
  - All copies are deleted when the app starts.
- **Once you share something, the receiving app has its own copy.** MyVault cannot take it back.
- Choosing a photo opens the system picker outside MyVault. For up to 3 minutes this does not count
  as leaving the app, so the vault does not lock while you pick. Normal auto-lock still applies.
- **MyVault never makes a QR from an account number.** It only stores and shares the QR your bank
  gave you.
- Bank names are plain text with a generic coloured badge. MyVault uses no bank logos or branding.

## Tested

**Unit tests** (`core/src/test/.../SpaceTest.kt`, 7 tests):
- leading zeros;
- share text never includes notes;
- search;
- encryption at rest and lock/unlock;
- replace/delete clean-up;
- sync of entries and QR images, including a conflict copy;
- backup and restore-merge.

**Web compatibility:** the Kotlin-written vault and sync file contain `space` and `asset` records,
and the web verifier opens and syncs them unchanged.

**Emulator (Android 16), on a debug build with the demo vault:**
- add an account, with the account number normalised and leading zeros kept;
- import a QR through the Photo Picker without the vault locking;
- QR found, then crop to QR;
- favourite, then save;
- detail screen with the masked number and reveal;
- copy (pasted back: `000123456789`);
- search by digits;
- long-press menu;
- full-screen QR;
- standalone QR entry, including validation when the QR is missing;
- replace and remove the QR;
- delete;
- lock and unlock.

**Sharesheet (emulator):**
- **Opened:** the Sharesheet opened for Number, for QR, for Image + text (the system preview showed
  the image and the text) and for Payment card.
- **Scannable:** the shared QR file and the payment card were pulled from the emulator and read by
  a separate QR decoder.

## Not verified

- **WhatsApp and Telegram.** These apps are not on the emulator, so it is not verified what they do
  with image + text, and nothing was sent to a real recipient.
- **Real bank QR codes.** Scanning by banking apps was not checked with a real DuitNow QR. The test
  image holds plain text.
- **Google Drive with a real account.** Sync was tested only with the in-memory remote in the unit
  tests.
