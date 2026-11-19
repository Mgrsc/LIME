#include <algorithm>
#include <cmath>
#include <queue>
#include <tuple>
#include <rime/gear/grammar.h>
#include <rime/gear/sentence_search.h>

namespace rime {

namespace {

struct SentenceWord {
  const DictEntry* entry;
  size_t end;
};

struct SentenceSuffix {
  vector<SentenceWord> words;
  string text;
  Code code;
  double score = 0.0;
};

struct SuffixCursor {
  const DictEntry* entry;
  size_t end;
  const vector<SentenceSuffix>* suffixes;
  size_t index;
  double score;
  size_t order;
};

struct PreferSuffixCursor {
  bool operator()(const SuffixCursor& left, const SuffixCursor& right) const {
    if (left.score != right.score)
      return left.score < right.score;
    if (left.order != right.order)
      return left.order > right.order;
    return left.index > right.index;
  }
};

bool SpendExpansion(SentenceSearchBudget& budget) {
  if (!budget.expansions_remaining)
    return false;
  --budget.expansions_remaining;
  return true;
}

}

double SentenceWordScore(const DictEntry& entry) {
  return Grammar::Evaluate({}, entry.text, entry.weight, false, nullptr);
}

double SentenceScoreToQuality(double score) {
  const double word_base_score =
      Grammar::Evaluate({}, {}, 0.0, false, nullptr);
  return std::exp(score - word_base_score);
}

bool MergeSentenceEntry(DictEntryList& entries, const an<DictEntry>& entry) {
  if (!entry || !entry->IsExactMatch())
    return false;
  const auto duplicate = std::find_if(
      entries.begin(), entries.end(), [&](const auto& other) {
        return entry->text == other->text && entry->code == other->code;
      });
  if (duplicate == entries.end()) {
    entries.push_back(entry);
    return true;
  }
  if (std::make_tuple(!entry->is_fuzzy, entry->weight, entry->quality_len) >
      std::make_tuple(!(*duplicate)->is_fuzzy, (*duplicate)->weight,
                      (*duplicate)->quality_len))
    *duplicate = entry;
  return false;
}

void LimitSentenceCandidates(deque<an<Sentence>>& sentences,
                             size_t max_sentences, double cutoff_threshold,
                             const an<Sentence>& protected_sentence) {
  deque<an<Sentence>> retained;
  set<string> seen;
  size_t count = protected_sentence ? 1 : 0;
  double last_weight = protected_sentence ? protected_sentence->weight() : 0.0;
  const double acceleration = max_sentences ? 1.0 - 1.0 / max_sentences : 0.0;
  if (protected_sentence)
    seen.insert(protected_sentence->text());
  for (auto& sentence : sentences) {
    if (count >= max_sentences)
      break;
    if (!seen.insert(sentence->text()).second)
      continue;
    const double weight = sentence->weight();
    if (count > 0) {
      if (last_weight - weight > cutoff_threshold * std::fabs(last_weight))
        break;
      cutoff_threshold *= acceleration;
    }
    last_weight = weight;
    ++count;
    retained.push_back(std::move(sentence));
  }
  sentences = std::move(retained);
}

deque<an<Sentence>> SearchSentences(const WordGraph& graph, size_t total_length,
                                    const Language* language, size_t capacity,
                                    SentenceSearchBudget& budget) {
  if (!capacity || !total_length)
    return {};
  map<size_t, vector<SentenceSuffix>> states;
  states[total_length].push_back({});
  for (auto vertex = graph.rbegin(); vertex != graph.rend(); ++vertex) {
    const size_t start = vertex->first;
    if (start >= total_length)
      continue;
    std::priority_queue<SuffixCursor, vector<SuffixCursor>, PreferSuffixCursor>
        queue;
    size_t order = 0;
    for (const auto& edge : vertex->second) {
      const size_t end = edge.first;
      if (end <= start || end > total_length ||
          (start == 0 && end == total_length))
        continue;
      auto suffixes = states.find(end);
      if (suffixes == states.end() || suffixes->second.empty())
        continue;
      for (const auto& entry : edge.second) {
        if (!entry || !entry->IsExactMatch())
          continue;
        if (!SpendExpansion(budget))
          break;
        queue.push({entry.get(), end, &suffixes->second, 0,
                    SentenceWordScore(*entry) + suffixes->second[0].score,
                    order++});
      }
    }
    auto& paths = states[start];
    set<pair<string, Code>> seen;
    while (!queue.empty() && paths.size() < capacity &&
           SpendExpansion(budget)) {
      auto cursor = queue.top();
      queue.pop();
      const auto& suffix = cursor.suffixes->at(cursor.index);
      SentenceSuffix next;
      next.text = cursor.entry->text + suffix.text;
      next.code = cursor.entry->code;
      next.code.insert(next.code.end(), suffix.code.begin(), suffix.code.end());
      next.score = cursor.score;
      if (seen.emplace(next.text, next.code).second) {
        next.words.push_back({cursor.entry, cursor.end});
        next.words.insert(next.words.end(), suffix.words.begin(),
                          suffix.words.end());
        paths.push_back(std::move(next));
      }
      if (++cursor.index < cursor.suffixes->size()) {
        cursor.score = SentenceWordScore(*cursor.entry) +
                       cursor.suffixes->at(cursor.index).score;
        queue.push(cursor);
      }
    }
  }
  deque<an<Sentence>> result;
  auto complete = states.find(0);
  if (complete == states.end())
    return result;
  for (const auto& path : complete->second) {
    auto sentence = New<Sentence>(language);
    double score = 0.0;
    for (const auto& word : path.words) {
      score += SentenceWordScore(*word.entry);
      sentence->Extend(*word.entry, word.end, score);
    }
    result.push_back(std::move(sentence));
  }
  return result;
}

}
