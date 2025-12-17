#include <SDL.h>
#include <SDL_main.h>
#include <SDL_system.h>
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
#include "BlockFile.h"

#include "android_input_system.h"
#include "gles_presenter.h"
#include "gles_stub_render3d.h"
#include "Graphics/New3D/New3D.h"

// Minimal OSD glue -----------------------------------------------------------

static std::string JoinPath(const std::string& a, const std::string& b);

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

struct Super3Host {
  Util::Config::Node config{"Global"};
  AndroidInputSystem inputSystem;
  CInputs inputs{&inputSystem};
  StubOutputs outputs;
  GlesStubRender3D stub3d;
  std::unique_ptr<New3D::CNew3D> new3d;
  IRender3D* render3d = &stub3d;
  CRender2D render2d{config};

  std::unique_ptr<GameLoader> loader;
  std::unique_ptr<CModel3> model3;
  Game game;
  ROMSet roms;
  std::atomic<bool> ready{false};
  std::string userDataRoot;

  Super3Host() { ApplyDefaults(); }

  void SetUserDataRoot(std::string root) { userDataRoot = std::move(root); }

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
    config.Set("SoundVolume", "200");
    config.Set("MusicVolume", "200");
    config.Set("LegacySoundDSP", false);
    config.Set("New3DEngine", false);
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
  }

  bool InitLoader(const std::string& gamesXml) {
    ApplyDefaults();
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

  void RunFrame() {
    if (ready.load(std::memory_order_acquire) && model3) {
      // Poll inputs once per frame (matches desktop OSD flow).
      // Display geometry is used for mouse/lightgun normalization; for Android touch/key
      // it mainly keeps the input system in a sane state.
      inputs.Poll(&game, 0, 0, 496, 384);
      model3->RunFrame();
    }
  }

  bool InstallNew3D(unsigned totalXRes, unsigned totalYRes)
  {
    if (!model3 || new3d)
      return !!new3d;

    // Letterbox the Model 3 496x384 output into the physical drawable area.
    // We pass the "viewable area" size to New3D so it doesn't wide-screen expand the frustum.
    const float targetAR = 496.0f / 384.0f;
    unsigned viewW = totalXRes;
    unsigned viewH = totalYRes;
    const float windowAR = (float)totalXRes / (float)totalYRes;
    if (windowAR > targetAR) {
      viewH = totalYRes;
      viewW = (unsigned)std::lround((double)totalYRes * targetAR);
    } else {
      viewW = totalXRes;
      viewH = (unsigned)std::lround((double)totalXRes / targetAR);
    }
    const unsigned xOff = (totalXRes - viewW) / 2;
    const unsigned yOff = (totalYRes - viewH) / 2;

    SDL_Log("Initializing New3D (GLES) ...");
    new3d = std::make_unique<New3D::CNew3D>(config, game.name);
    if (new3d->Init(xOff, yOff, viewW, viewH, viewW, viewH) != 0)
    {
      SDL_LogError(SDL_LOG_CATEGORY_APPLICATION, "New3D Init failed");
      new3d.reset();
      return false;
    }

    render3d = new3d.get();
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

  SDL_Window* window = SDL_CreateWindow(
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

  SDL_GLContext gl = SDL_GL_CreateContext(window);
  if (!gl) {
    SDL_LogError(SDL_LOG_CATEGORY_APPLICATION, "SDL_GL_CreateContext failed: %s", SDL_GetError());
    SDL_DestroyWindow(window);
    SDL_Quit();
    return 1;
  }

  SDL_GL_MakeCurrent(window, gl);
  SDL_GL_SetSwapInterval(1);

  GlesPresenter presenter;
  if (!presenter.Init()) {
    SDL_LogError(SDL_LOG_CATEGORY_APPLICATION, "GlesPresenter.Init failed");
    SDL_GL_DeleteContext(gl);
    SDL_DestroyWindow(window);
    SDL_Quit();
    return 1;
  }

  Super3Host host;
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
        new3dAttached = host.InstallNew3D((unsigned)winW, (unsigned)winH);
      }

      if (new3dAttached) {
        // 3D path: let New3D draw into the default framebuffer from inside the core.
        glViewport(0, 0, winW, winH);
        glClearColor(0.f, 0.f, 0.f, 1.f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT | GL_STENCIL_BUFFER_BIT);
        host.RunFrame();

        // Overlay TileGen top layers (HUD/menus) on top of 3D.
        presenter.Resize(winW, winH);
        if (host.render2d.HasTopSurface()) {
          const uint32_t* pixels = host.render2d.GetTopSurfaceARGB();
          presenter.UpdateFrameARGB(pixels, (int)host.render2d.GetFrameWidth(), (int)host.render2d.GetFrameHeight());
          presenter.Render(true);
        }
      } else {
        // 2D-only path: keep showing TileGen software output.
        host.RunFrame();
        presenter.Resize(winW, winH);
        glClearColor(0.f, 0.f, 0.f, 1.f);
        glClear(GL_COLOR_BUFFER_BIT);
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
  return 0;
}
