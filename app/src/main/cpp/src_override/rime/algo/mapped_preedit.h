#ifndef RIME_MAPPED_PREEDIT_H_
#define RIME_MAPPED_PREEDIT_H_

#include <algorithm>
#include <string>
#include <utility>
#include <vector>
#include <utf8.h>

namespace rime {

struct PreeditSourceSpan {
  size_t start = 0;
  size_t end = 0;

  bool operator==(const PreeditSourceSpan& other) const {
    return start == other.start && end == other.end;
  }
};

struct MappedPreedit {
  std::string text;
  std::vector<PreeditSourceSpan> sources;

  static MappedPreedit Input(const std::string& input, size_t start = 0) {
    if (!utf8::is_valid(input.begin(), input.end()))
      return Literal(input, {start, start + input.size()});
    MappedPreedit result;
    result.text = input;
    auto cursor = input.begin();
    while (cursor != input.end()) {
      const auto begin = cursor;
      utf8::next(cursor, input.end());
      result.sources.insert(
          result.sources.end(), cursor - begin,
          {start + size_t(begin - input.begin()),
           start + size_t(cursor - input.begin())});
    }
    return result;
  }

  static MappedPreedit Literal(const std::string& text,
                              PreeditSourceSpan source) {
    return {text, std::vector<PreeditSourceSpan>(text.size(), source)};
  }

  bool valid() const {
    return text.size() == sources.size() && utf8::is_valid(text.begin(), text.end());
  }

  PreeditSourceSpan Source(size_t start, size_t end) const {
    if (start >= end) {
      const size_t position = start < sources.size()
                                  ? sources[start].start
                                  : sources.empty() ? 0 : sources.back().end;
      return {position, position};
    }
    PreeditSourceSpan result = sources.at(start);
    for (size_t i = start + 1; i < end; ++i) {
      result.start = std::min(result.start, sources.at(i).start);
      result.end = std::max(result.end, sources.at(i).end);
    }
    return result;
  }

  MappedPreedit Slice(size_t start, size_t end) const {
    return {text.substr(start, end - start),
            std::vector<PreeditSourceSpan>(sources.begin() + start,
                                          sources.begin() + end)};
  }

  void Append(const MappedPreedit& other) {
    text += other.text;
    sources.insert(sources.end(), other.sources.begin(), other.sources.end());
  }

  int Utf16Offset(size_t byte_offset) const {
    if (!valid())
      return 0;
    auto cursor = text.begin();
    int offset = 0;
    while (cursor != text.end()) {
      auto next = cursor;
      const auto codepoint = utf8::next(next, text.end());
      if (size_t(next - text.begin()) > byte_offset)
        break;
      offset += codepoint > 0xffff ? 2 : 1;
      cursor = next;
    }
    return offset;
  }

  std::vector<std::pair<int, int>> Utf16Boundaries() const {
    std::vector<std::pair<int, int>> result;
    if (!valid() || text.empty())
      return result;
    result.emplace_back(0, sources.front().start);
    auto cursor = text.begin();
    int display = 0;
    while (cursor != text.end()) {
      const auto codepoint = utf8::next(cursor, text.end());
      display += codepoint > 0xffff ? 2 : 1;
      const size_t offset = cursor - text.begin();
      if (offset == text.size()) {
        result.emplace_back(display, sources.back().end);
      } else if (sources[offset - 1].end <= sources[offset].start) {
        result.emplace_back(display, sources[offset].start);
      }
    }
    return result;
  }

  size_t ByteOffsetForRaw(size_t raw) const {
    if (!valid())
      return 0;
    size_t best = 0;
    size_t distance = size_t(-1);
    auto consider = [&](size_t offset, size_t source) {
      const auto current = source > raw ? source - raw : raw - source;
      if (current <= distance) {
        distance = current;
        best = offset;
      }
    };
    if (!sources.empty()) {
      consider(0, sources.front().start);
      auto cursor = text.begin();
      while (cursor != text.end()) {
        utf8::next(cursor, text.end());
        const size_t offset = cursor - text.begin();
        if (offset == text.size())
          consider(offset, sources.back().end);
        else if (sources[offset - 1].end <= sources[offset].start)
          consider(offset, sources[offset].start);
      }
    }
    return best;
  }

  void ExtendLast(PreeditSourceSpan source) {
    if (sources.empty())
      return;
    const auto previous = sources.back();
    for (auto i = sources.rbegin(); i != sources.rend() && *i == previous; ++i) {
      i->start = std::min(i->start, source.start);
      i->end = std::max(i->end, source.end);
    }
  }

  void ExtendFirst(PreeditSourceSpan source) {
    if (sources.empty())
      return;
    const auto previous = sources.front();
    for (auto& item : sources) {
      if (!(item == previous))
        break;
      item.start = std::min(item.start, source.start);
      item.end = std::max(item.end, source.end);
    }
  }
};

}

#endif
