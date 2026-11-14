//
// Copyright RIME Developers
// Distributed under the BSD License
//
// 2011-06-19 GONG Chen <chen.sst@gmail.com>
//
#include <boost/algorithm/string.hpp>
#include <boost/range/adaptor/reversed.hpp>
#include <rime/candidate.h>
#include <atomic>
#include <rime/composition.h>
#include <rime/menu.h>
#include <rime/gear/translator_commons.h>

namespace rime {

namespace {
std::atomic<uint64_t> composition_revision{0};
}

void Composition::Reset(const string& input) {
  revision_ = ++composition_revision;
  Segmentation::Reset(input);
}

void Composition::Reset(size_t num_segments) {
  revision_ = ++composition_revision;
  Segmentation::Reset(num_segments);
}

bool Composition::HasFinishedComposition() const {
  if (empty())
    return false;
  size_t k = size() - 1;
  if (k > 0 && at(k).start == at(k).end)
    --k;
  return at(k).status >= Segment::kSelected;
}

Preedit Composition::GetPreedit(const string& full_input,
                                size_t caret_pos,
                                const string& caret) const {
  Preedit preedit;
  auto& output = preedit.mapped;
  preedit.caret_pos = string::npos;
  size_t start = 0;
  size_t end = 0;
  for (size_t i = 0; i < size(); ++i) {
    start = end;
    if (caret_pos == start) {
      preedit.caret_pos = output.text.length();
    }
    auto cand = at(i).GetSelectedCandidate();
    if (i < size() - 1) {
      if (cand) {
        end = cand->end();
        output.Append(MappedPreedit::Literal(cand->text(), {start, end}));
      } else {
        end = at(i).end;
        if (!at(i).HasTag("phony")) {
          output.Append(MappedPreedit::Input(input_.substr(start, end - start), start));
        }
      }
    } else {
      preedit.sel_start = output.text.length();
      preedit.sel_end = string::npos;
      if (cand && !cand->preedit().empty()) {
        end = cand->end();
        const auto phrase = As<Phrase>(Candidate::GetGenuineCandidate(cand));
        auto mapped = phrase ? phrase->mapped_preedit() : MappedPreedit();
        if (mapped.text != cand->preedit() || !mapped.valid())
          mapped = MappedPreedit::Literal(cand->preedit(), {start, end});
        auto caret_placeholder = cand->preedit().find('\t');
        if (caret_placeholder != string::npos) {
          output.Append(mapped.Slice(0, caret_placeholder));
          if (caret_pos == end && end == full_input.length()) {
            preedit.sel_end = preedit.sel_start + caret_placeholder;
            preedit.caret_pos = preedit.sel_end;
            output.Append(MappedPreedit::Literal(
                cand->preedit().substr(caret_placeholder + 1), {end, end}));
          }
        } else {
          output.Append(mapped);
        }
      } else {
        end = at(i).end;
        output.Append(MappedPreedit::Input(input_.substr(start, end - start), start));
      }
      if (preedit.sel_end == string::npos) {
        preedit.sel_end = output.text.length();
      }
    }
  }
  if (end < input_.length()) {
    output.Append(MappedPreedit::Input(input_.substr(end), end));
    end = input_.length();
  }
  if (preedit.caret_pos == string::npos) {
    preedit.caret_pos = output.ByteOffsetForRaw(caret_pos);
  }
  if (end < full_input.length()) {
    output.Append(MappedPreedit::Input(full_input.substr(end), end));
  }
  auto prompt = caret + GetPrompt();
  if (!prompt.empty()) {
    auto suffix = output.Slice(preedit.caret_pos, output.text.size());
    output = output.Slice(0, preedit.caret_pos);
    output.Append(MappedPreedit::Literal(prompt, {caret_pos, caret_pos}));
    output.Append(suffix);
    if (preedit.caret_pos < preedit.sel_start) {
      preedit.sel_start += prompt.length();
    }
    if (preedit.caret_pos < preedit.sel_end) {
      preedit.sel_end += prompt.length();
    }
  }
  preedit.text = output.text;
  return preedit;
}

string Composition::GetPrompt() const {
  return empty() ? string() : back().prompt;
}

string Composition::GetCommitText() const {
  string result;
  size_t end = 0;
  for (const Segment& seg : *this) {
    if (auto cand = seg.GetSelectedCandidate()) {
      end = cand->end();
      result += cand->text();
    } else {
      end = seg.end;
      if (!seg.HasTag("phony")) {
        result += input_.substr(seg.start, seg.end - seg.start);
      }
    }
  }
  if (input_.length() > end) {
    result += input_.substr(end);
  }
  return result;
}

string Composition::GetScriptText(bool keep_selection) const {
  string result;
  size_t start = 0;
  size_t end = 0;
  for (const Segment& seg : *this) {
    auto cand = seg.GetSelectedCandidate();
    start = end;
    end = cand ? cand->end() : seg.end;
    if (keep_selection && cand && !cand->text().empty() &&
        seg.status >= Segment::kSelected)
      result += cand->text();
    else if (cand && !cand->preedit().empty())
      result += boost::erase_first_copy(cand->preedit(), "\t");
    else if (!seg.HasTag("phony"))
      result += input_.substr(start, end - start);
  }
  if (input_.length() > end) {
    result += input_.substr(end);
  }
  return result;
}

string Composition::GetDebugText() const {
  string result;
  int i = 0;
  for (const Segment& seg : *this) {
    if (i++ > 0)
      result += "|";
    if (!seg.tags.empty()) {
      result += "{";
      int j = 0;
      for (const string& tag : seg.tags) {
        if (j++ > 0)
          result += ",";
        result += tag;
      }
      result += "}";
    }
    result += input_.substr(seg.start, seg.end - seg.start);
    if (auto cand = seg.GetSelectedCandidate()) {
      result += "=>";
      result += cand->text();
    }
  }
  return result;
}

string Composition::GetTextBefore(size_t pos) const {
  if (empty())
    return string();
  for (const auto& seg : boost::adaptors::reverse(*this)) {
    if (seg.end <= pos) {
      if (auto cand = seg.GetSelectedCandidate()) {
        return cand->text();
      }
    }
  }
  return string();
}

}
