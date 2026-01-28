# SUPER3

SUPER3 is an open-source Sega Model 3 emulator for Android.
It is based on [Supermodel](https://github.com/trzy/Supermodel) by `trzy`, with additional work derived from the [model3emu-code-sinden (ARM branch)](https://github.com/DirtBagXon/model3emu-code-sinden/tree/arm) fork.

<a href="https://play.google.com/store/apps/details?id=com.izzy2lost.super3&pcampaignid=web_share">
  <img src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png" alt="Get it on Google Play" height="80"/>
</a>

This app does **not** include any games, ROMs, BIOS files, or copyrighted game content. You must supply your own legally obtained game ROMs.

## Features

- Sega Model 3 emulation (compatibility varies by title/device).
- On-screen touch controls (Service/Test/Coin/Start and game-specific controls).
- Virtual on-screen steering wheel for driving games (when supported by the title).
- Save states (10 slots) with automatic screenshot thumbnails and tap-to-preview.
- In-game pause/resume button.
- Game flyers (front/back) in the launcher list.

## Video settings (Resolution & Widescreen)

From the main game list, open the side menu (toolbar "menu" icon) and look under **Video**.

- **Resolution:** Native (496x384) and integer scale options (2x through 8x). Higher values look sharper but can reduce performance.
- **Match device resolution:** Sets the internal resolution to your device's exact pixel resolution (landscape). This also enables **Widescreen** and **Wide background**.
- **Widescreen:** Expands the horizontal field of view (Supermodel `-wide-screen`). At very wide aspect ratios some games may cull objects at the edges.
- **Wide background:** Stretches the 2D background layers when using widescreen (Supermodel `-wide-bg`).
- **Enhanced Real3D (experimental):** Enables more desktop-like Real3D rendering (multi-pass transparency compositing + scroll fog). This can improve visuals in some games/areas, but may reduce performance on lower-end devices.

## Files & Storage

- Save states and thumbnails are stored under the app's user data directory in `Saves/` (for example: `.../Android/data/<package>/files/super3/Saves/`).
- The optional sync/export workflow uses Android's Storage Access Framework, and only accesses the folder(s) you explicitly choose.

## Legal & Privacy

- **No games included:** SUPER3 does not ship with ROMs or game data, and is not intended to facilitate piracy.
- **You must own the games:** Only use ROMs you are legally permitted to use in your region.
- **Trademarks:** "SEGA" and all associated game names, trademarks, and copyrighted works are the property of their respective owners.
- **Not affiliated:** This project is not affiliated with or endorsed by SEGA or any arcade hardware manufacturer.
- **Network:** Emulation can run offline; Flyer view may download artwork from GitHub.
- **Storage:** Settings, save states, and screenshots are stored locally; optional sync/export is limited to the folder you choose in the system picker.

## Source & Third-Party

- Source code: [https://github.com/izzy2lost/Super3](https://github.com/izzy2lost/Super3)
- Upstream emulator: [https://github.com/trzy/Supermodel](https://github.com/trzy/Supermodel)
- Reference fork (ARM work): [https://github.com/DirtBagXon/model3emu-code-sinden/tree/arm](https://github.com/DirtBagXon/model3emu-code-sinden/tree/arm)
- SDL2: [https://github.com/libsdl-org/SDL](https://github.com/libsdl-org/SDL)

## License

This project is licensed under the [GNU GPL v3.0](LICENSE).
