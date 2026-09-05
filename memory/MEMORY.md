# Memory Index

- [Project overview](project_overview.md) — what the stickman project is + its public GitHub repo URL
- [Gemini quota per project](gemini_quota_per_project.md) — why characters go silent, per-project key quota; corrected 2026-08-22, AQ.Ab8RN6... keys ARE valid
- [Windows dev workflow](windows_dev_workflow.md) — how to restart/rebuild locally (env reload, JDK for Android, adb path)
- [Feedback: workflow style](feedback_workflow_style.md) — terse multi-tasking messages, delegates execution, engages with clarifying questions
- [Android per-node model mode](android_multi_provider_mode.md) — WIP: each character/node can pick its own AI provider (gemini/openai/groq/openrouter), uncommitted as of 2026-08-16
- [Stick Nodes WASM renderer prototype](sn_proto_wasm_renderer.md) — scratch project (outside repo) parsing/rendering real .nodes rig files via WASM; head-circle + octagon-head bugs fixed 2026-08-16
- [Android rig renderer](android_rig_renderer.md) — Red now renders via procedural Kotlin rig (RigView/PoseLibrary) incl. wall-climbing; only Stand/Walk visually confirmed on-device so far
- [Android screen vision](android_screen_vision.md) — characters see phone screenshots via accessibility service every AI tick; had a real crash loop (fixed) — see this before assuming it "just works"
- [Android chat + movement fixes](android_chat_and_movement.md) — chat now a floating top-right button (no more app-switch on tap), and errors no longer trigger random walking
- [PC settings window](pc_settings_window.md) — pre-launch menu for provider/API keys/which characters appear; had to fix config.js getters + Shimeji's separate ActiveShimeji config
- [Sleep/tiredness system](sleep_tiredness_system.md) — auto-forced sleep (night + awake-duration) on Android and PC, not yet visually confirmed live (thresholds are long)
- [PC engine replacement](pc_engine_replacement.md) — Shimeji-ee fully replaced by native JS rig renderer windows (commit a57608d), drag via OS window drag, some caveats unverified live
- [Android idle sway + wander](android_idle_sway_and_repetition.md) — anti-repetition, idle sway, autonomous wander; "not responding" bug root-caused to invalid API key, not code
- [Installer stale, no window opens](installer_stale_no_window.md) — release/ installer built 2026-08-15 predates PC engine replacement, missing renderer files main.js needs; fix is npm run dist + reinstall
- [PC per-character AI provider](pc_per_character_provider.md) — ported Android's per-node provider picker to PC (config.js/pcSettings.js/settings UI); confirmed committed 2026-08-23
- [Custom character creator](custom_character_creator.md) — "crear tu propio stickman": clone Red/TCO rigs, face/gender/accessory, color picker, edit-existing; v2.4→v2.15/v1.4→v1.14
