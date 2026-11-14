#ifndef RIME_LOOKUP_BUDGET_H_
#define RIME_LOOKUP_BUDGET_H_

#include <cstddef>

namespace rime {

struct LookupBudget {
  size_t traversal_remaining = 0;
  size_t entries_remaining = 0;

  LookupBudget Share(size_t remaining_positions) const {
    if (!remaining_positions)
      return {};
    return {traversal_remaining / remaining_positions,
            entries_remaining / remaining_positions};
  }

  bool Traverse() {
    if (!traversal_remaining)
      return false;
    --traversal_remaining;
    return true;
  }

  bool ReadEntry() {
    if (!entries_remaining)
      return false;
    --entries_remaining;
    return true;
  }
};

}

#endif
