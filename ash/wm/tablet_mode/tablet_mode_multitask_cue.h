// Copyright 2022 The Chromium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef ASH_WM_TABLET_MODE_TABLET_MODE_MULTITASK_CUE_H_
#define ASH_WM_TABLET_MODE_TABLET_MODE_MULTITASK_CUE_H_

#include <memory>

#include "ash/ash_export.h"
#include "ash/wm/window_state_observer.h"
#include "base/memory/raw_ptr.h"
#include "base/scoped_observation.h"
#include "base/timer/timer.h"
#include "chromeos/ui/frame/multitask_menu/multitask_menu_nudge_controller.h"
#include "ui/aura/window_observer.h"
#include "ui/compositor/layer.h"
#include "ui/wm/public/activation_change_observer.h"

namespace ash {

// Creates a cue (draggable bar) at the top center of an app window when it is
// activated in tablet mode. Only one cue exists at a time.
// TODO(b/279220838): Rename to TabletModeMultitaskCueController for better
// clarity.
class ASH_EXPORT TabletModeMultitaskCue : aura::WindowObserver,
                                          wm::ActivationChangeObserver,
                                          WindowStateObserver {
 public:
  static constexpr int kCueHeight = 4;
  static constexpr int kCueYOffset = 6;

  TabletModeMultitaskCue();

  TabletModeMultitaskCue(const TabletModeMultitaskCue&) = delete;
  TabletModeMultitaskCue& operator=(const TabletModeMultitaskCue&) = delete;

  ~TabletModeMultitaskCue() override;

  ui::Layer* cue_layer() { return cue_layer_.get(); }
  chromeos::MultitaskMenuNudgeController* nudge_controller() {
    return &nudge_controller_;
  }

  // Shows the cue if `active_window` is an maximizable app window that is not
  // floated. Also sets a `OneShotTimer` to dismiss the cue after a short
  // duration.
  void MaybeShowCue(aura::Window* active_window);

  // Returns false if the cue cannot be shown on `window` (e.g., non-app
  // windows), and true otherwise.
  bool CanShowCue(aura::Window* window) const;

  // Dismisses the cue from the screen and cleans up the pointers and
  // observers related to its parent window.
  void DismissCue();

  // Resets the position of the cue back to the top of `window_` if the cue is
  // still visible.
  void ResetPosition();

  // aura::WindowObserver:
  void OnWindowDestroying(aura::Window* window) override;
  void OnWindowBoundsChanged(aura::Window* window,
                             const gfx::Rect& old_bounds,
                             const gfx::Rect& new_bounds,
                             ui::PropertyChangeReason reason) override;

  // wm::ActivationChangeObserver:
  void OnWindowActivated(ActivationReason reason,
                         aura::Window* gained_active,
                         aura::Window* lost_active) override;

  // WindowStateObserver:
  void OnPostWindowStateTypeChange(WindowState* window_state,
                                   chromeos::WindowStateType old_type) override;

  void FireCueDismissTimerForTesting() { cue_dismiss_timer_.FireNow(); }

 private:
  // Updates the bounds of the cue relative to the window if the window is
  // still available.
  void UpdateCueBounds();

  // Fades the cue out over a short duration if it is still active, then cleans
  // up via `DismissCue`. If already fading out, returns immediately.
  void OnTimerFinished();

  // The app window that the cue is associated with.
  raw_ptr<aura::Window, ExperimentalAsh> window_ = nullptr;

  // Handles showing the educational nudge for the tablet multitask menu.
  chromeos::MultitaskMenuNudgeController nudge_controller_;

  // The solid color layer that represents the cue.
  std::unique_ptr<ui::Layer> cue_layer_;

  // Observes for window destruction or bounds changes.
  base::ScopedObservation<aura::Window, aura::WindowObserver>
      window_observation_{this};

  // Dismisses the cue after a short amount of time if it is still active.
  base::OneShotTimer cue_dismiss_timer_;
};

}  // namespace ash

#endif  // ASH_WM_TABLET_MODE_TABLET_MODE_MULTITASK_CUE_H_
