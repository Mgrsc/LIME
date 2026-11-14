//
// Copyright RIME Developers
// Distributed under the BSD License
//
// 2011-06-19 GONG Chen <chen.sst@gmail.com>
//
#ifndef RIME_COMPOSITION_H_
#define RIME_COMPOSITION_H_

#include <cstdint>
#include <rime/segmentation.h>
#include <rime/algo/mapped_preedit.h>

namespace rime {

struct Preedit {
  string text;
  MappedPreedit mapped;
  size_t caret_pos = 0;
  size_t sel_start = 0;
  size_t sel_end = 0;
};

class Composition : public Segmentation {
 public:
  Composition() = default;

  void Reset(const string& input);
  void Reset(size_t num_segments);
  uint64_t revision() const { return revision_; }

  bool HasFinishedComposition() const;
  Preedit GetPreedit(const string& full_input,
                     size_t caret_pos,
                     const string& caret) const;
  string GetPrompt() const;
  string GetCommitText() const;
  string GetScriptText(bool keep_selection = true) const;
  RIME_DLL string GetDebugText() const;
  string GetTextBefore(size_t pos) const;

 private:
  uint64_t revision_ = 0;
};

}

#endif
