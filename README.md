# SUPER3

SUPER3 is an open-source Sega Model 3 emulator for Android.
It is based on the Supermodel emulator by `trzy`, with additional work derived from the `model3emu-code-sinden` fork (ARM branch).

This app does **not** include any games, ROMs, BIOS files, or copyrighted game content. You must supply your own legally obtained game ROMs.

## Features
- Sega Model 3 emulation (compatibility varies by title/device).
- On-screen touch controls (Service/Test/Coin/Start and game-specific controls).
- Virtual on-screen steering wheel for driving games (when supported by the title).
- Save states (10 slots) with automatic screenshot thumbnails and tap-to-preview.
- In-game pause/resume button.

## Video settings (Resolution & Widescreen)
From the main game list, open the side menu (toolbar “menu” icon) and look under **Video**.

- **Resolution:** Native (496×384) and integer scale options (2× through 8×). Higher values look sharper but can reduce performance.
- **Match device resolution:** Sets the internal resolution to your device’s exact pixel resolution (landscape). This also enables **Widescreen** and **Wide background**.
- **Widescreen:** Expands the horizontal field of view (Supermodel `-wide-screen`). At very wide aspect ratios some games may cull objects at the edges.
- **Wide background:** Stretches the 2D background layers when using widescreen (Supermodel `-wide-bg`).

## Files & Storage
- Save states and thumbnails are stored under the app’s user data directory in `Saves/` (for example: `.../Android/data/<package>/files/super3/Saves/`).
- The app may offer an optional “sync”/export workflow using Android’s Storage Access Framework. If enabled, the app only accesses the folder(s) you explicitly choose.

## Legal / Google Play Policy Notes
- **No games included:** SUPER3 does not ship with ROMs or game data, and it is not intended to facilitate piracy.
- **You must own the games:** Only use ROMs you are legally permitted to use in your region.
- **Trademarks:** “SEGA” and all associated game names, trademarks, and copyrighted works are the property of their respective owners.
- **Not affiliated:** This project is not affiliated with or endorsed by SEGA or any arcade hardware manufacturer.

## Privacy
- SUPER3 does not require the `INTERNET` permission and is designed to run offline.
- The app stores emulator settings, save states, and screenshots locally on your device.
- If you opt into selecting a user folder for syncing/export, access is limited to the folder you select via the system file picker.

## Upstream / Credits
- Supermodel (upstream): https://github.com/trzy/Supermodel
- Model3Emu fork used as reference (ARM branch): https://github.com/DirtBagXon/model3emu-code-sinden/tree/arm
- SDL2 is used for the Android shell and input/audio/video integration.

## License
This project is licensed under the GNU GPL v3.0 (see `LICENSE`).
If you distribute builds (including on Google Play), GPL requires providing the corresponding source code to recipients.
