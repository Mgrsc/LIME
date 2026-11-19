#pragma once

#include <rime/context.h>

namespace lime {

inline constexpr char kExplicitLearningSelection[] =
    "_lime_explicit_learning_selection";

class ScopedExplicitLearningSelection {
 public:
  ScopedExplicitLearningSelection(rime::Context* context, bool explicit_selection)
      : context_(context) {
    if (context_) {
      previous_ = context_->get_property(kExplicitLearningSelection);
      context_->set_property(kExplicitLearningSelection, explicit_selection ? "1" : "0");
    }
  }

  ~ScopedExplicitLearningSelection() {
    if (context_)
      context_->set_property(kExplicitLearningSelection, previous_);
  }

  ScopedExplicitLearningSelection(const ScopedExplicitLearningSelection&) = delete;
  ScopedExplicitLearningSelection& operator=(
      const ScopedExplicitLearningSelection&) = delete;

 private:
  rime::Context* context_;
  std::string previous_;
};

}
