#include <SDL.h>
#include <SDL_main.h>
#include <SDL_system.h>
#include <jni.h>
#include <string>
#include <filesystem>
#include <optional>
#include <vector>
#include <algorithm>
#include <atomic>
#include <thread>
#include <cstring>

#include <GLES3/gl3.h>

#include "GameLoader.h"
#include "Model3/Model3.h"
#include "ROMSet.h"
#include "Graphics/Render2D.h"
#include "Graphics/IRender3D.h"
#include "Inputs/Inputs.h"
#include "Inputs/InputSystem.h"
#include "OSD/Outputs.h"
#include "OSD/Audio.h"
#include "OSD/Logger.h"
#include "Util/NewConfig.h"
#include "Util/ConfigBuilders.h"
#include "Version.h"
#include "BlockFile.h"

#include "android_input_system.h"
#include "gles_presenter.h"
#include "gles_stub_render3d.h"
#include "Graphics/New3D/New3D.h"

// Minimal OSD glue -----------------------------------------------------------

static std::string JoinPath(const std::string& a, const std::string& b);

class CompositedRender3D final : public IRender3D
{
public:
  CompositedRender3D(IRender3D* inner, CRender2D* render2d, GlesPresenter* presenter)
    : m_inner(inner), m_render2d(render2d), m_presenter(presenter)
  {
  }

  void RenderFrame(void) override
  {
    if (m_presenter && m_render2d && m_render2d->HasFrame())
    {
      const uint32_t* pixels = m_render2d->GetFrameBufferRGBA();
      m_presenter->UpdateFrameARGB(pixels, (int)m_render2d->GetFrameWidth(), (int)m_render2d->GetFrameHeight());
      m_presenter->Render(false);
    }
    if (m_inner)
      m_inner->RenderFrame();
  }

  void BeginFrame(void) override
  {
    if (m_inner)
      m_inner->BeginFrame();
  }

  void EndFrame(void) override
  {
    if (m_inner)
      m_inner->EndFrame();
  }

  void UploadTextures(unsigned level, unsigned x, unsigned y, unsigned width, unsigned height) override
  {
    if (m_inner)
      m_inner->UploadTextures(level, x, y, width, height);
  }

  void AttachMemory(const uint32_t* cullingRAMLoPtr,
                    const uint32_t* cullingRAMHiPtr,
                    const uint32_t* polyRAMPtr,
                    const uint32_t* vromPtr,
                    const uint16_t* textureRAMPtr) override
  {
    if (m_inner)
      m_inner->AttachMemory(cullingRAMLoPtr, cullingRAMHiPtr, polyRAMPtr, vromPtr, textureRAMPtr);
  }

  void SetStepping(int stepping) override
  {
    if (m_inner)
      m_inner->SetStepping(stepping);
  }

  bool Init(unsigned xOffset, unsigned yOffset, unsigned xRes, unsigned yRes, unsigned totalXRes, unsigned totalYRes) override
  {
    if (!m_inner)
      return true;
    return m_inner->Init(xOffset, yOffset, xRes, yRes, totalXRes, totalYRes);
  }

  void SetSunClamp(bool enable) override
  {
    if (m_inner)
      m_inner->SetSunClamp(enable);
  }

  void SetSignedShade(bool enable) override
  {
    if (m_inner)
      m_inner->SetSignedShade(enable);
  }

  float GetLosValue(int layer) override
  {
    if (!m_inner)
      return 0.0f;
    return m_inner->GetLosValue(layer);
  }

private:
  IRender3D* m_inner = nullptr;
  CRender2D* m_render2d = nullptr;
  GlesPresenter* m_presenter = nullptr;
};

class NullRender3D : public IRender3D {
public:
  void RenderFrame() override {}
  void BeginFrame() override {}
  void EndFrame() override {}
  void UploadTextures(unsigned, unsigned, unsigned, unsigned, unsigned) override {}
  void AttachMemory(const uint32_t*, const uint32_t*, const uint32_t*, const uint32_t*, const uint16_t*) override {}
  void SetStepping(int) override {}
  bool Init(unsigned, unsigned, unsigned, unsigned, unsigned, unsigned) override { return true; }
  void SetSunClamp(bool) override {}
  void SetSignedShade(bool) override {}
  float GetLosValue(int) override { return 0.0f; }
};

class StubOutputs : public COutputs {
public:
  bool Initialize() override { return true; }
  void Attached() override {}
protected:
  void SendOutput(EOutputs, UINT8, UINT8) override {}
};

// Emulator host --------------------------------------------------------------

static struct Super3Host* g_host = nullptr;

struct Super3Host {
  static constexpr int32_t STATE_FILE_VERSION = 3;
  Util::Config::Node config{"Global"};
  AndroidInputSystem inputSystem;
  CInputs inputs{&inputSystem};
  StubOutputs outputs;
  GlesStubRender3D stub3d;
  std::unique_ptr<New3D::CNew3D> new3d;
  std::unique_ptr<IRender3D> composited3d;
  IRender3D* render3d = &stub3d;
  CRender2D render2d{config};

  std::unique_ptr<GameLoader> loader;
  std::unique_ptr<CModel3> model3;
  Game game;
  ROMSet roms;
  std::atomic<bool> ready{false};
  std::string userDataRoot;
  unsigned saveSlot = 0;
  std::atomic<int> requestSaveSlot{-1};
  std::atomic<int> requestLoadSlot{-1};
  std::atomic<int> requestMenuPause{-1}; // -1=no change, 0=resume, 1=pause
  bool menuPaused = false;
  bool threadsPausedByMenu = false;

  Super3Host() { ApplyDefaults(); }

  void SetUserDataRoot(std::string root)
  {
    userDataRoot = std::move(root);
    if (userDataRoot.empty())
      return;
    try {
      std::filesystem::create_directories(userDataRoot);
      std::filesystem::create_directories(JoinPath(userDataRoot, "Saves"));
      std::filesystem::current_path(userDataRoot);
      SDL_Log("User data root: %s", userDataRoot.c_str());
    } catch (...) {
      // Ignore any path errors; caller can still override with absolute paths.
    }
  }

  std::string NvramPathForGame() const
  {
    std::string base = userDataRoot.empty() ? "super3" : userDataRoot;
    return JoinPath(JoinPath(base, "NVRAM"), game.name + ".nv");
  }

  void LoadNVRAMIfPresent()
  {
    static constexpr int32_t NVRAM_FILE_VERSION = 0;
    const std::string filePath = NvramPathForGame();
    if (!std::filesystem::exists(filePath) || !model3)
      return;

    CBlockFile nv;
    if (OKAY != nv.Load(filePath))
      return;
    if (OKAY != nv.FindBlock("Supermodel NVRAM State"))
      return;

    int32_t fileVersion = -1;
    nv.Read(&fileVersion, sizeof(fileVersion));
    if (fileVersion != NVRAM_FILE_VERSION)
      return;

    model3->LoadNVRAM(&nv);
    nv.Close();
    SDL_Log("Loaded NVRAM: %s", filePath.c_str());
  }

  void SaveNVRAM()
  {
    static constexpr int32_t NVRAM_FILE_VERSION = 0;
    if (!model3 || game.name.empty())
      return;

    const std::string filePath = NvramPathForGame();
    try {
      std::filesystem::create_directories(std::filesystem::path(filePath).parent_path());
    } catch (...) {
      // ignore
    }

    CBlockFile nv;
    if (OKAY != nv.Create(filePath, "Supermodel NVRAM State", "Super3 Android NVRAM"))
      return;

    nv.Write(&NVRAM_FILE_VERSION, sizeof(NVRAM_FILE_VERSION));
    nv.Write(game.name);
    model3->SaveNVRAM(&nv);
    nv.Close();
    SDL_Log("Saved NVRAM: %s", filePath.c_str());
  }

  void ApplyDefaults() {
    // Enable the core's sound-board thread so audio can stay smooth even if
    // rendering/input causes occasional stalls on Android.
    config.Set("MultiThreaded", true);
    config.Set("GPUMultiThreaded", false);
    config.Set("EmulateSound", true);
    config.Set("EmulateDSB", true);
    config.Set("Balance", "0");
    config.Set("BalanceLeftRight", "0");
    config.Set("BalanceFrontRear", "0");
    config.Set("NbSoundChannels", "4");
    config.Set("SoundFreq", "57.6");
    // Supermodel.ini commonly uses 200 as "100%".
    config.Set("SoundVolume", "100");
    config.Set("MusicVolume", "150");
    config.Set("LegacySoundDSP", false);
    config.Set("New3DEngine", true);
    config.Set("New3DAccurate", false);
    config.Set("QuadRendering", false);
    config.Set("FlipStereo", false);
    // The core expects this node to exist (throws std::range_error otherwise).
    config.Set("PowerPCFrequency", "50");
    config.Set("InputSystem", "sdl");
    config.Set("ABSMiceOnly", true);
    config.Set("Outputs", "none");
    config.Set("ForceFeedback", false);
    config.Set("Network", false);
    config.Set("SimulateNet", false);
    config.Set("XResolution", "496");
    config.Set("YResolution", "384");

    // Lightgun games: reload immediately when tapping the off-screen button.
    config.Set("InputAutoTrigger", "1");
    config.Set("InputAutoTrigger2", "1");

    // Minimal default input bindings (keyboard scancodes). On Android, our input
    // system synthesizes these via touch/controller.
    config.Set("InputCoin1", "KEY_5");
    config.Set("InputStart1", "KEY_1");
    config.Set("InputServiceA", "KEY_F1");
    config.Set("InputTestA", "KEY_F2");
    config.Set("InputJoyUp", "KEY_UP");
    config.Set("InputJoyDown", "KEY_DOWN");
    config.Set("InputJoyLeft", "KEY_LEFT");
    config.Set("InputJoyRight", "KEY_RIGHT");
    config.Set("InputSteeringLeft", "KEY_LEFT");
    config.Set("InputSteeringRight", "KEY_RIGHT");
      config.Set("InputAccelerator", "KEY_W");
      config.Set("InputBrake", "KEY_S");
      config.Set("UISaveState", "KEY_F5");
      config.Set("UIChangeSlot", "KEY_F6");
      config.Set("UILoadState", "KEY_F7");
      inputSystem.ApplyConfig(config);
    }

  void ApplyAndroidHardOverrides()
  {
    auto ensureKeyboardFallback = [&](const char* cfgKey, const char* defaultKeyToken) {
      std::string v = config[cfgKey].ValueAsDefault<std::string>("");
      if (v.empty()) {
        config.Set(cfgKey, defaultKeyToken);
        return;
      }
      std::string upper = v;
      std::transform(upper.begin(), upper.end(), upper.begin(), [](unsigned char c) { return (char)std::toupper(c); });
      if (upper.find("KEY_") == std::string::npos) {
        config.Set(cfgKey, std::string(defaultKeyToken) + "," + v);
      }
    };

    // Settings that must remain stable on Android (unsupported or not yet wired).
    config.Set("InputSystem", "sdl");
    config.Set("Outputs", "none");
    config.Set("ForceFeedback", false);
    config.Set("Network", false);
    config.Set("SimulateNet", false);

    // Ensure required nodes exist / sane.
    config.Set("PowerPCFrequency", "50");

    // Keep the current Android renderer path stable for now.
    config.Set("New3DEngine", false);
    config.Set("QuadRendering", false);

    // Allow user-specified framebuffer sizes. Clamp to sane bounds so we don't
    // accidentally allocate absurdly large render targets.
    {
      unsigned xRes = 496;
      unsigned yRes = 384;
      try { xRes = config["XResolution"].ValueAsDefault<unsigned>(496); } catch (...) { xRes = 496; }
      try { yRes = config["YResolution"].ValueAsDefault<unsigned>(384); } catch (...) { yRes = 384; }

      if (xRes < 496u || yRes < 384u || xRes > 8192u || yRes > 8192u) {
        xRes = 496;
        yRes = 384;
      }

      config.Set("XResolution", std::to_string(xRes));
      config.Set("YResolution", std::to_string(yRes));
    }

    // Ensure touch zones always have a working keyboard mapping even if the user remaps to joystick-only.
    ensureKeyboardFallback("InputCoin1", "KEY_5");
    ensureKeyboardFallback("InputStart1", "KEY_1");
    ensureKeyboardFallback("InputServiceA", "KEY_F1");
    ensureKeyboardFallback("InputTestA", "KEY_F2");
    ensureKeyboardFallback("InputJoyUp", "KEY_UP");
    ensureKeyboardFallback("InputJoyDown", "KEY_DOWN");
    ensureKeyboardFallback("InputJoyLeft", "KEY_LEFT");
    ensureKeyboardFallback("InputJoyRight", "KEY_RIGHT");
    ensureKeyboardFallback("InputSteeringLeft", "KEY_LEFT");
    ensureKeyboardFallback("InputSteeringRight", "KEY_RIGHT");
    ensureKeyboardFallback("InputAccelerator", "KEY_W");
    ensureKeyboardFallback("InputBrake", "KEY_S");
    ensureKeyboardFallback("InputPunch", "KEY_A");
    ensureKeyboardFallback("InputKick", "KEY_S");
    ensureKeyboardFallback("InputGuard", "KEY_D");
    ensureKeyboardFallback("InputEscape", "KEY_F");
    ensureKeyboardFallback("InputShift", "KEY_A");
    ensureKeyboardFallback("InputBeat", "KEY_S");
    ensureKeyboardFallback("InputCharge", "KEY_D");
    ensureKeyboardFallback("InputJump", "KEY_F");

    // Racing view buttons.
    ensureKeyboardFallback("InputViewChange", "KEY_A");
    ensureKeyboardFallback("InputVR1", "KEY_A");
    ensureKeyboardFallback("InputVR2", "KEY_S");
    ensureKeyboardFallback("InputVR3", "KEY_D");
    ensureKeyboardFallback("InputVR4", "KEY_F");

    // Virtual-On (twin joystick) macros/buttons: keep keyboard fallbacks so touch controls work even
    // if the user remaps to joystick-only.
    ensureKeyboardFallback("InputTwinJoyTurnLeft", "KEY_Q");
    ensureKeyboardFallback("InputTwinJoyTurnRight", "KEY_W");
    ensureKeyboardFallback("InputTwinJoyForward", "KEY_UP");
    ensureKeyboardFallback("InputTwinJoyReverse", "KEY_DOWN");
    ensureKeyboardFallback("InputTwinJoyStrafeLeft", "KEY_LEFT");
    ensureKeyboardFallback("InputTwinJoyStrafeRight", "KEY_RIGHT");
    ensureKeyboardFallback("InputTwinJoyJump", "KEY_E");
    ensureKeyboardFallback("InputTwinJoyCrouch", "KEY_R");
    ensureKeyboardFallback("InputTwinJoyShot1", "KEY_A");
    ensureKeyboardFallback("InputTwinJoyShot2", "KEY_S");
    ensureKeyboardFallback("InputTwinJoyTurbo1", "KEY_Z");
    ensureKeyboardFallback("InputTwinJoyTurbo2", "KEY_X");

    inputSystem.ApplyConfig(config);
  }

  void ApplyIniOverrides(const std::string& gameSectionName)
  {
    const std::string base = userDataRoot.empty() ? std::string("super3") : userDataRoot;
    const std::string iniPath = JoinPath(JoinPath(base, "Config"), "Supermodel.ini");
    if (!std::filesystem::exists(iniPath)) {
      SDL_Log("Supermodel.ini not found at %s (using built-in defaults)", iniPath.c_str());
      return;
    }

    Util::Config::Node iniConfig{"Global"};
    if (Util::Config::FromINIFile(&iniConfig, iniPath)) {
      SDL_LogError(SDL_LOG_CATEGORY_APPLICATION, "Failed to parse %s (using built-in defaults)", iniPath.c_str());
      return;
    }

    // Merge global settings: INI overrides built-in defaults.
    {
      Util::Config::Node merged{"Global"};
      Util::Config::MergeINISections(&merged, config, iniConfig);
      config = merged;
    }

    // Merge game-specific settings if present.
    if (!gameSectionName.empty()) {
      const Util::Config::Node* section = iniConfig.TryGet(gameSectionName);
      if (section != nullptr) {
        Util::Config::Node merged{"Global"};
        Util::Config::MergeINISections(&merged, config, *section);
        config = merged;
      }
    }

    ApplyAndroidHardOverrides();
    inputSystem.ApplyConfig(config);
  }

  bool InitLoader(const std::string& gamesXml) {
    ApplyDefaults();
    ApplyIniOverrides(std::string());
    ApplyAndroidHardOverrides();
    if (!std::filesystem::exists(gamesXml)) {
      ErrorLog("Games XML not found at %s", gamesXml.c_str());
      SDL_LogError(SDL_LOG_CATEGORY_APPLICATION, "Games XML not found at %s", gamesXml.c_str());
      return false;
    }
    loader = std::make_unique<GameLoader>(gamesXml);
    return true;
  }

  bool LoadGameFromZip(const std::string& zipPath, const std::string& gameName) {
    try {
      // Reinforce defaults before each load in case the config was mutated.
      ApplyDefaults();
      SDL_Log("LoadGameFromZip: zip=%s game=%s", zipPath.c_str(), gameName.empty() ? "<auto>" : gameName.c_str());

      if (!loader) return false;
      if (!std::filesystem::exists(zipPath)) {
        ErrorLog("ROM zip missing: %s", zipPath.c_str());
        SDL_LogError(SDL_LOG_CATEGORY_APPLICATION, "ROM zip missing: %s", zipPath.c_str());
        return false;
      }

      SDL_Log("Loading ROM definitions...");
      if (loader->Load(&game, &roms, zipPath, gameName)) {
        ErrorLog("Failed to load ROM definition for %s", zipPath.c_str());
        SDL_LogError(SDL_LOG_CATEGORY_APPLICATION, "Failed to load ROM definition for %s", zipPath.c_str());
        return false;
      }

      const bool gunGame =
        (game.inputs & (Game::INPUT_GUN1 | Game::INPUT_GUN2 | Game::INPUT_ANALOG_GUN1 | Game::INPUT_ANALOG_GUN2)) != 0;
      inputSystem.SetGunTouchEnabled(gunGame);

      const bool analogGunGame = (game.inputs & (Game::INPUT_ANALOG_GUN1 | Game::INPUT_ANALOG_GUN2)) != 0;
      inputSystem.SetVirtualAnalogGunEnabled(analogGunGame);

      const bool vehicleGame = (game.inputs & (Game::INPUT_VEHICLE | Game::INPUT_HARLEY)) != 0;
      inputSystem.SetVirtualWheelEnabled(vehicleGame);

      const bool shift4 = (game.inputs & Game::INPUT_SHIFT4) != 0;
      const bool shiftUpDown = (game.inputs & Game::INPUT_SHIFTUPDOWN) != 0;
      inputSystem.SetVirtualShifterMode(shift4, shiftUpDown);

      // Apply Supermodel.ini overrides (Global + [ game ]) after the loader has determined game->name.
      ApplyIniOverrides(game.name);
      ApplyAndroidHardOverrides();

      // Initialize inputs before attaching to model
      if (!inputs.Initialize()) {
        ErrorLog("Inputs initialization failed");
        SDL_LogError(SDL_LOG_CATEGORY_APPLICATION, "Inputs initialization failed");
        return false;
      }
      inputs.LoadFromConfig(config);

      model3 = std::make_unique<CModel3>(config);

      // Most of the legacy core uses OKAY=0 / FAIL=1 semantics even when the
      // return type is bool. Treat non-zero as failure.
      SDL_Log("Model3 Init...");
      if (model3->Init() != 0) {
        ErrorLog("Model3 Init failed");
        SDL_LogError(SDL_LOG_CATEGORY_APPLICATION, "Model3 Init failed");
        return false;
      }
      SDL_Log("Model3 LoadGame...");
      if (model3->LoadGame(game, roms) != 0) {
        ErrorLog("Model3 LoadGame failed");
        SDL_LogError(SDL_LOG_CATEGORY_APPLICATION, "Model3 LoadGame failed");
        return false;
      }

      // Desktop SDL flow attaches inputs/outputs before Reset(); some drive boards
      // emit force-feedback stop commands during reset and require inputs.
      SDL_Log("Attaching inputs/outputs...");
      model3->AttachInputs(&inputs);
      model3->AttachOutputs(&outputs);

      // TileGen allocates and wires VRAM/palette/register pointers during Init();
      // attach renderers after Init()/LoadGame() so Render2D sees valid pointers.
      SDL_Log("Attaching renderers...");
      model3->AttachRenderers(&render2d, render3d);

      // Establish initial CPU/device state (ppc_reset(), etc).
      SDL_Log("Model3 Reset...");
      model3->Reset();

      // Persisted test menu settings (e.g., Daytona 2 Link ID = SINGLE).
      LoadNVRAMIfPresent();

      SDL_Log("LoadGameFromZip complete.");
      ready.store(true, std::memory_order_release);
      return true;
    } catch (const std::exception& ex) {
      ErrorLog("Exception during LoadGameFromZip: %s", ex.what());
      SDL_LogError(SDL_LOG_CATEGORY_APPLICATION, "Exception during LoadGameFromZip: %s", ex.what());
      ready.store(false, std::memory_order_release);
      model3.reset();
      return false;
    }
  }

    void ApplyMenuPaused(bool paused)
    {
      menuPaused = paused;
      if (!model3)
        return;

      if (paused) {
        if (!threadsPausedByMenu) {
          SDL_Log("Menu pause ON");
          model3->PauseThreads();
          SetAudioEnabled(false);
          threadsPausedByMenu = true;
        }
      } else {
        if (threadsPausedByMenu) {
          SDL_Log("Menu pause OFF");
          model3->ResumeThreads();
          SetAudioEnabled(true);
          threadsPausedByMenu = false;
        }
      }
    }

    void RunFrame() {
      if (ready.load(std::memory_order_acquire) && model3) {
        const int pauseReq = requestMenuPause.exchange(-1, std::memory_order_acq_rel);
        if (pauseReq != -1) {
          ApplyMenuPaused(pauseReq == 1);
        }

        const int saveReq = requestSaveSlot.exchange(-1, std::memory_order_acq_rel);
        if (saveReq >= 0) {
          const unsigned slot = static_cast<unsigned>(saveReq) % 10u;
          const bool wasPaused = threadsPausedByMenu;
          saveSlot = slot;
          SDL_Log("UI save state requested (slot %u)", saveSlot);
          if (!wasPaused) {
            model3->PauseThreads();
            SetAudioEnabled(false);
          }
          SaveState();
          if (!wasPaused) {
            model3->ResumeThreads();
            SetAudioEnabled(true);
          }
        }

        const int loadReq = requestLoadSlot.exchange(-1, std::memory_order_acq_rel);
        if (loadReq >= 0) {
          const unsigned slot = static_cast<unsigned>(loadReq) % 10u;
          const bool wasPaused = threadsPausedByMenu;
          saveSlot = slot;
          SDL_Log("UI load state requested (slot %u)", saveSlot);
          if (!wasPaused) {
            model3->PauseThreads();
            SetAudioEnabled(false);
          }
          LoadState();
          if (!wasPaused) {
            model3->ResumeThreads();
            SetAudioEnabled(true);
          }
        }

        // Poll inputs once per frame (matches desktop OSD flow).
        // Display geometry is used for mouse/lightgun normalization; for Android touch/key
        // it mainly keeps the input system in a sane state.
        inputs.Poll(&game, 0, 0, 496, 384);

        // If a physical keyboard is attached, allow the canonical hotkeys too.
        if (!threadsPausedByMenu) {
          if (inputs.uiSaveState && inputs.uiSaveState->Pressed()) {
            requestSaveSlot.store(static_cast<int>(saveSlot), std::memory_order_release);
          } else if (inputs.uiChangeSlot && inputs.uiChangeSlot->Pressed()) {
            saveSlot = (saveSlot + 1) % 10;
            SDL_Log("Save slot: %u", saveSlot);
          } else if (inputs.uiLoadState && inputs.uiLoadState->Pressed()) {
            requestLoadSlot.store(static_cast<int>(saveSlot), std::memory_order_release);
          }
        }

        if (threadsPausedByMenu) model3->RenderFrame();
        else model3->RunFrame();
      }
    }

    std::string SaveStatePath() const
    {
      const std::string base = userDataRoot.empty() ? std::string("super3") : userDataRoot;
      return JoinPath(JoinPath(base, "Saves"), game.name + ".st" + std::to_string(saveSlot));
    }

    void SaveState()
    {
      if (!model3 || game.name.empty())
        return;

      const std::string filePath = SaveStatePath();
      try {
        std::filesystem::create_directories(std::filesystem::path(filePath).parent_path());
      } catch (...) {
        // ignore
      }

      CBlockFile SaveState;
      if (OKAY != SaveState.Create(filePath, "Supermodel Save State", "Supermodel Version " SUPERMODEL_VERSION))
      {
        ErrorLog("Unable to save state to '%s'.", filePath.c_str());
        SDL_LogError(SDL_LOG_CATEGORY_APPLICATION, "Unable to save state to '%s'.", filePath.c_str());
        return;
      }

      int32_t fileVersion = STATE_FILE_VERSION;
      SaveState.Write(&fileVersion, sizeof(fileVersion));
      SaveState.Write(game.name);
      model3->SaveState(&SaveState);
      SaveState.Close();
      SDL_Log("Saved state to '%s'.", filePath.c_str());
    }

    void LoadState()
    {
      if (!model3 || game.name.empty())
        return;

      const std::string filePath = SaveStatePath();
      CBlockFile SaveState;
      if (OKAY != SaveState.Load(filePath))
      {
        ErrorLog("Unable to load state from '%s'.", filePath.c_str());
        SDL_LogError(SDL_LOG_CATEGORY_APPLICATION, "Unable to load state from '%s'.", filePath.c_str());
        return;
      }

      if (OKAY != SaveState.FindBlock("Supermodel Save State"))
      {
        ErrorLog("'%s' does not appear to be a valid save state file.", filePath.c_str());
        SDL_LogError(SDL_LOG_CATEGORY_APPLICATION, "'%s' does not appear to be a valid save state file.", filePath.c_str());
        return;
      }

      int32_t fileVersion;
      SaveState.Read(&fileVersion, sizeof(fileVersion));
      if (fileVersion != STATE_FILE_VERSION)
      {
        ErrorLog("'%s' is incompatible with this version of Supermodel.", filePath.c_str());
        SDL_LogError(SDL_LOG_CATEGORY_APPLICATION, "'%s' is incompatible with this version of Supermodel.", filePath.c_str());
        return;
      }

      model3->LoadState(&SaveState);
      SaveState.Close();
      SDL_Log("Loaded state from '%s'.", filePath.c_str());
    }

  bool InstallNew3D(GlesPresenter* presenter,
                    unsigned xOff,
                    unsigned yOff,
                    unsigned xRes,
                    unsigned yRes,
                    unsigned totalXRes,
                    unsigned totalYRes)
  {
    if (!model3 || new3d)
      return !!new3d;

    SDL_Log("Initializing New3D (GLES) ...");
    SDL_Log("New3DAccurate=%d", config["New3DAccurate"].ValueAsDefault<bool>(false) ? 1 : 0);
    new3d = std::make_unique<New3D::CNew3D>(config, game.name);
    if (new3d->Init(xOff, yOff, xRes, yRes, totalXRes, totalYRes) != 0)
    {
      SDL_LogError(SDL_LOG_CATEGORY_APPLICATION, "New3D Init failed");
      new3d.reset();
      return false;
    }

    composited3d = std::make_unique<CompositedRender3D>(new3d.get(), &render2d, presenter);
    render3d = composited3d.get();
    model3->AttachRenderers(&render2d, render3d);
    SDL_Log("New3D attached");
    return true;
  }
};

static std::string JoinPath(const std::string& a, const std::string& b)
{
  if (a.empty()) return b;
  if (b.empty()) return a;
  if (a.back() == '/' || a.back() == '\\') return a + b;
  return a + "/" + b;
}

static std::optional<std::string> FindFirstExisting(const std::vector<std::string>& candidates)
{
  for (const auto& p : candidates) {
    if (!p.empty() && std::filesystem::exists(p)) {
      return p;
    }
  }
  return std::nullopt;
}

// SDL entry point; currently a stub until the emulator core is hooked up.
extern "C" int SDL_main(int argc, char* argv[]) {
  (void)argc;
  (void)argv;

  // Encourage landscape on Android; otherwise SDL may default to a "user"
  // orientation that respects rotation lock.
  SDL_SetHint(SDL_HINT_ORIENTATIONS, "LandscapeLeft LandscapeRight");
  // Prevent the device from dimming/sleeping while the emulator is running.
  SDL_DisableScreenSaver();

  if (SDL_Init(SDL_INIT_VIDEO | SDL_INIT_GAMECONTROLLER | SDL_INIT_AUDIO) != 0) {
    SDL_LogError(SDL_LOG_CATEGORY_APPLICATION, "SDL_Init failed: %s", SDL_GetError());
    return 1;
  }

  SDL_GL_SetAttribute(SDL_GL_CONTEXT_PROFILE_MASK, SDL_GL_CONTEXT_PROFILE_ES);
  SDL_GL_SetAttribute(SDL_GL_CONTEXT_MAJOR_VERSION, 3);
  SDL_GL_SetAttribute(SDL_GL_CONTEXT_MINOR_VERSION, 0);
  SDL_GL_SetAttribute(SDL_GL_DOUBLEBUFFER, 1);
  SDL_GL_SetAttribute(SDL_GL_DEPTH_SIZE, 24);
  SDL_GL_SetAttribute(SDL_GL_STENCIL_SIZE, 8);

  struct GlConfig {
    int depth;
    int stencil;
  };
  const GlConfig configs[] = {
    {24, 8},
    {24, 0},
    {16, 8},
    {16, 0},
  };

  SDL_Window* window = nullptr;
  SDL_GLContext gl = nullptr;
  for (const auto& cfg : configs) {
    SDL_GL_SetAttribute(SDL_GL_DEPTH_SIZE, cfg.depth);
    SDL_GL_SetAttribute(SDL_GL_STENCIL_SIZE, cfg.stencil);

    window = SDL_CreateWindow(
      "Super3 (SDL bootstrap)",
      SDL_WINDOWPOS_CENTERED,
      SDL_WINDOWPOS_CENTERED,
      1280,
      720,
      SDL_WINDOW_SHOWN | SDL_WINDOW_RESIZABLE | SDL_WINDOW_OPENGL
    );

    if (!window) {
      SDL_LogError(SDL_LOG_CATEGORY_APPLICATION, "SDL_CreateWindow failed: %s", SDL_GetError());
      SDL_Quit();
      return 1;
    }

    gl = SDL_GL_CreateContext(window);
    if (gl) {
      SDL_Log("SDL GL context created (depth=%d, stencil=%d)", cfg.depth, cfg.stencil);
      break;
    }

    SDL_LogError(SDL_LOG_CATEGORY_APPLICATION, "SDL_GL_CreateContext failed (depth=%d stencil=%d): %s",
                 cfg.depth, cfg.stencil, SDL_GetError());
    SDL_DestroyWindow(window);
    window = nullptr;
  }

  if (!gl) {
    SDL_Quit();
    return 1;
  }

  SDL_GL_MakeCurrent(window, gl);
  SDL_GL_SetSwapInterval(1);

  SDL_Log("GL_VENDOR=%s", reinterpret_cast<const char*>(glGetString(GL_VENDOR)));
  SDL_Log("GL_RENDERER=%s", reinterpret_cast<const char*>(glGetString(GL_RENDERER)));
  SDL_Log("GL_VERSION=%s", reinterpret_cast<const char*>(glGetString(GL_VERSION)));
  SDL_Log("GLSL_VERSION=%s", reinterpret_cast<const char*>(glGetString(GL_SHADING_LANGUAGE_VERSION)));

  GlesPresenter presenter;
  if (!presenter.Init()) {
    SDL_LogError(SDL_LOG_CATEGORY_APPLICATION, "GlesPresenter.Init failed");
    SDL_GL_DeleteContext(gl);
    SDL_DestroyWindow(window);
    SDL_Quit();
    return 1;
  }

  Super3Host host;
  g_host = &host;
  // Initialize renderer backends up-front. The core will attach VRAM/palette/register
  // pointers later (after it has initialized the tile generator).
  host.render2d.Init(0, 0, 496, 384, 496, 384);

  // Locate resources. Prefer app-specific external storage (no runtime perms),
  // but also probe common legacy locations for dev convenience.
  const char* external = SDL_AndroidGetExternalStoragePath();
  const char* internal = SDL_AndroidGetInternalStoragePath();

  std::vector<std::string> roots;
  if (external) roots.emplace_back(external);
  if (internal) roots.emplace_back(internal);
  roots.emplace_back("/storage/emulated/0");
  roots.emplace_back("/sdcard");

  // Where we store runtime data (NVRAM, etc.). Prefer app-specific external storage.
  std::string preferredDataRoot;
  if (external) preferredDataRoot = JoinPath(external, "super3");
  else if (internal) preferredDataRoot = JoinPath(internal, "super3");
  else preferredDataRoot = "/storage/emulated/0/super3";
  host.SetUserDataRoot(preferredDataRoot);

  std::vector<std::string> gamesCandidates;
  std::vector<std::string> romCandidates;
  for (const auto& r : roots) {
    gamesCandidates.emplace_back(JoinPath(JoinPath(r, "super3"), "Games.xml"));
    romCandidates.emplace_back(JoinPath(JoinPath(JoinPath(r, "super3"), "roms"), "dayto2pe.zip"));
  }

  std::string gamesXml = FindFirstExisting(gamesCandidates).value_or(gamesCandidates.front());
  std::string romZip = FindFirstExisting(romCandidates).value_or(romCandidates.front());
  std::string gameName; // leave empty to auto-detect from zip unless argv overrides
  if (argc > 1 && argv[1]) {
    romZip = argv[1];
  }
  if (argc > 2 && argv[2]) {
    gameName = argv[2];
  }
  if (argc > 3 && argv[3]) {
    gamesXml = argv[3];
  }
  if (argc > 4 && argv[4] && argv[4][0] != '\0') {
    host.SetUserDataRoot(argv[4]);
  }

  SDL_Log("Super3 paths: Games.xml=%s ROM=%s", gamesXml.c_str(), romZip.c_str());

  // Kick off loading on a worker thread so we can keep presenting frames and
  // avoid Android's "skipped frames" warnings / ANRs during heavy I/O.
  std::atomic<int> loadState{0}; // 0=loading, 1=ready, -1=failed
  std::thread loaderThread([&]() {
    if (!host.InitLoader(gamesXml)) {
      loadState.store(-1, std::memory_order_release);
      return;
    }
    if (!host.LoadGameFromZip(romZip, gameName)) {
      loadState.store(-1, std::memory_order_release);
      return;
    }
    loadState.store(1, std::memory_order_release);
  });
  loaderThread.detach();

  bool running = true;
  uint32_t lastStatusLog = SDL_GetTicks();
  bool backgrounded = false;
  bool loggedControls = false;
  bool audioOpened = false;
  bool new3dAttached = false;
  while (running) {
    SDL_Event ev;
    while (SDL_PollEvent(&ev)) {
      host.inputSystem.HandleEvent(ev);
      if (ev.type == SDL_QUIT) {
        // On Android the surface can be destroyed/recreated when the app is
        // backgrounded; SDL may emit SDL_QUIT in some of these transitions.
        // Prefer quitting only on explicit user intent (back button) or app
        // termination events.
        SDL_Log("SDL_QUIT received (ignored)");
      } else if (ev.type == SDL_KEYDOWN && ev.key.keysym.sym == SDLK_AC_BACK) {
        running = false; // back button
      } else if (ev.type == SDL_WINDOWEVENT) {
        if (ev.window.event == SDL_WINDOWEVENT_FOCUS_GAINED) SDL_Log("SDL window focus gained");
        if (ev.window.event == SDL_WINDOWEVENT_FOCUS_LOST) SDL_Log("SDL window focus lost");
        if (ev.window.event == SDL_WINDOWEVENT_SHOWN) SDL_Log("SDL window shown");
        if (ev.window.event == SDL_WINDOWEVENT_HIDDEN) SDL_Log("SDL window hidden");
        if (ev.window.event == SDL_WINDOWEVENT_MINIMIZED) SDL_Log("SDL window minimized");
        if (ev.window.event == SDL_WINDOWEVENT_RESTORED) SDL_Log("SDL window restored");
        if (ev.window.event == SDL_WINDOWEVENT_HIDDEN || ev.window.event == SDL_WINDOWEVENT_MINIMIZED) {
          backgrounded = true;
        } else if (ev.window.event == SDL_WINDOWEVENT_SHOWN || ev.window.event == SDL_WINDOWEVENT_RESTORED) {
          backgrounded = false;
        }
      } else if (ev.type == SDL_APP_WILLENTERBACKGROUND) {
        SDL_Log("SDL app will enter background");
      } else if (ev.type == SDL_APP_DIDENTERBACKGROUND) {
        SDL_Log("SDL app did enter background");
        backgrounded = true;
      } else if (ev.type == SDL_APP_WILLENTERFOREGROUND) {
        SDL_Log("SDL app will enter foreground");
      } else if (ev.type == SDL_APP_DIDENTERFOREGROUND) {
        SDL_Log("SDL app did enter foreground");
        backgrounded = false;
      } else if (ev.type == SDL_APP_TERMINATING) {
        SDL_Log("SDL app terminating");
        running = false;
      }
    }

    if (backgrounded) {
      SDL_Delay(50);
      continue;
    }

    int winW = 0, winH = 0;
    SDL_GL_GetDrawableSize(window, &winW, &winH);
    if (winW <= 0) winW = 1;
    if (winH <= 0) winH = 1;

    // Compute aspect-correct viewable area (xRes/yRes) centered within the drawable (totalXRes/totalYRes).
    const unsigned totalXRes = (unsigned)winW;
    const unsigned totalYRes = (unsigned)winH;
    unsigned xRes = totalXRes;
    unsigned yRes = totalYRes;
    const float model3AR = 496.0f / 384.0f;
    const float outAR = (float)totalXRes / (float)totalYRes;
    if (outAR > model3AR) {
      // output is wider => reduce width
      xRes = (unsigned)std::lround((double)totalYRes * model3AR);
      yRes = totalYRes;
    } else if (outAR < model3AR) {
      // output is taller => reduce height
      xRes = totalXRes;
      yRes = (unsigned)std::lround((double)totalXRes / model3AR);
    }
    const unsigned xOff = (totalXRes - xRes) / 2;
    const unsigned yOff = (totalYRes - yRes) / 2;

    const bool wideScreen = host.config["WideScreen"].ValueAsDefault<bool>(false);
    const bool wideBackground = host.config["WideBackground"].ValueAsDefault<bool>(false);

    // Clear full drawable (scissor off), then set scissor like desktop.
    glDisable(GL_SCISSOR_TEST);
    glViewport(0, 0, winW, winH);
    glClearColor(0.f, 0.f, 0.f, 1.f);
    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT | GL_STENCIL_BUFFER_BIT);

    const unsigned correction = (unsigned)(((double)yRes / 384.0) * 2.0 + 0.5);
    glEnable(GL_SCISSOR_TEST);
    if (wideScreen) {
      glScissor(0, (int)correction, (int)totalXRes, (int)(totalYRes - (correction * 2)));
    } else {
      glScissor((int)(xOff + correction), (int)(yOff + correction), (int)(xRes - (correction * 2)), (int)(yRes - (correction * 2)));
    }

    const int state = loadState.load(std::memory_order_acquire);
    if (state == 1) {
      if (!audioOpened) {
        audioOpened = true;
        SetAudioType(host.game.audio);
        if (!OpenAudio(host.config)) {
          SDL_LogError(SDL_LOG_CATEGORY_AUDIO, "OpenAudio failed (continuing without audio)");
        }
      }
      if (!loggedControls) {
        loggedControls = true;
        SDL_Log("Controls (touch): bottom-left=COIN, bottom-right=START, top-left=SERVICE, top-right=TEST, left-middle=DPAD/STEER, right-middle=THROTTLE/BRAKE");
      }

      if (!new3dAttached) {
        new3dAttached = host.InstallNew3D(&presenter, xOff, yOff, xRes, yRes, totalXRes, totalYRes);
      }

      if (new3dAttached) {
        // 3D path: let New3D draw into the default framebuffer from inside the core (scissored).
        presenter.Resize(winW, winH);
        presenter.SetStretch(false);
        host.RunFrame();

        // Overlay TileGen top layers (HUD/menus) on top of 3D.
        if (host.render2d.HasTopSurface()) {
          const uint32_t* pixels = host.render2d.GetTopSurfaceARGB();
          presenter.UpdateFrameARGB(pixels, (int)host.render2d.GetFrameWidth(), (int)host.render2d.GetFrameHeight());
          presenter.Render(true);
        }
      } else {
        // 2D-only path: keep showing TileGen software output.
        presenter.Resize(winW, winH);
        presenter.SetStretch(wideBackground);
        host.RunFrame();
        if (host.render2d.HasFrame()) {
          const uint32_t* pixels = host.render2d.GetFrameBufferRGBA();
          presenter.UpdateFrameARGB(pixels, (int)host.render2d.GetFrameWidth(), (int)host.render2d.GetFrameHeight());
          presenter.Render(false);
        }
      }
    }

    SDL_GL_SwapWindow(window);

    uint32_t t = SDL_GetTicks();
    if (t - lastStatusLog > 2000) {
      lastStatusLog = t;
      SDL_Log("Main loop alive; loadState=%d", state);
    }
  }

  presenter.Shutdown();
  if (loadState.load(std::memory_order_acquire) == 1) {
    host.SaveNVRAM();
  }
  CloseAudio();
  SDL_GL_DeleteContext(gl);
  SDL_DestroyWindow(window);
  SDL_Quit();
  g_host = nullptr;
  return 0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_izzy2lost_super3_Super3Activity_nativeSetMenuPaused(JNIEnv*, jobject, jboolean paused)
{
  if (!g_host)
    return JNI_FALSE;
  g_host->requestMenuPause.store(paused ? 1 : 0, std::memory_order_release);
  return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_izzy2lost_super3_Super3Activity_nativeRequestSaveState(JNIEnv*, jobject, jint slot)
{
  if (!g_host)
    return JNI_FALSE;
  const int clamped = (slot < 0) ? 0 : (slot > 9 ? 9 : slot);
  g_host->requestSaveSlot.store(clamped, std::memory_order_release);
  return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_izzy2lost_super3_Super3Activity_nativeRequestLoadState(JNIEnv*, jobject, jint slot)
{
  if (!g_host)
    return JNI_FALSE;
  const int clamped = (slot < 0) ? 0 : (slot > 9 ? 9 : slot);
  g_host->requestLoadSlot.store(clamped, std::memory_order_release);
  return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_izzy2lost_super3_Super3Activity_nativeGetLoadedGameName(JNIEnv* env, jobject)
{
  if (!g_host)
    return nullptr;
  if (g_host->game.name.empty())
    return nullptr;
  return env->NewStringUTF(g_host->game.name.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_izzy2lost_super3_Super3Activity_nativeSetGyroSteer(JNIEnv*, jobject, jfloat steer)
{
  if (!g_host)
    return JNI_FALSE;
  const float clamped = std::clamp((float)steer, -1.0f, 1.0f);
  g_host->inputSystem.SetGyroSteer(clamped);
  return JNI_TRUE;
}
