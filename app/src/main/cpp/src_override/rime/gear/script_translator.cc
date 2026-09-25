//
// Copyright RIME Developers
// Distributed under the BSD License
//
// Script translator
//
// 2011-07-10 GONG Chen <chen.sst@gmail.com>
//
#include <algorithm>
#include <stack>
#include <cmath>
#include <boost/algorithm/string/join.hpp>
#include <boost/range/adaptor/reversed.hpp>
#include <rime/common.h>
#include <rime/composition.h>
#include <rime/candidate.h>
#include <rime/config.h>
#include <rime/context.h>
#include <rime/engine.h>
#include <rime/key_event.h>
#include <rime/language.h>
#include <rime/schema.h>
#include <rime/translation.h>
#include <rime/algo/syllabifier.h>
#include <rime/dict/corrector.h>
#include <rime/dict/dictionary.h>
#include <rime/dict/user_dictionary.h>
#include <rime/gear/poet.h>
#include <rime/gear/script_translator.h>
#include <rime/gear/translator_commons.h>

// static const char* quote_left = "\xef\xbc\x88";
// static const char* quote_right = "\xef\xbc\x89";

namespace rime {

namespace {

struct SyllabifyTask {
  const Code& code;
  const SyllableGraph& graph;
  size_t target_pos;
  function<void(SyllabifyTask* task,
                size_t depth,
                size_t current_pos,
                size_t next_pos)>
      push;
  function<void(SyllabifyTask* task, size_t depth)> pop;
};

static bool syllabify_dfs(SyllabifyTask* task,
                          size_t depth,
                          size_t current_pos) {
  if (depth == task->code.size()) {
    return current_pos == task->target_pos;
  }
  SyllableId syllable_id = task->code.at(depth);
  auto z = task->graph.edges.find(current_pos);
  if (z == task->graph.edges.end())
    return false;
  // favor longer spellings
  for (const auto& y : boost::adaptors::reverse(z->second)) {
    size_t end_vertex_pos = y.first;
    if (end_vertex_pos > task->target_pos)
      continue;
    auto x = y.second.find(syllable_id);
    if (x != y.second.end()) {
      task->push(task, depth, current_pos, end_vertex_pos);
      if (syllabify_dfs(task, depth + 1, end_vertex_pos))
        return true;
      task->pop(task, depth);
    }
  }
  return false;
}

}  // anonymous namespace

class ScriptSyllabifier : public PhraseSyllabifier {
 public:
  ScriptSyllabifier(ScriptTranslator* translator,
                    Corrector* corrector,
                    const string& input,
                    size_t start)
      : translator_(translator),
        input_(input),
        start_(start),
        syllabifier_(translator->delimiters(),
                     translator->enable_completion(),
                     translator->strict_spelling()) {
    if (corrector) {
      syllabifier_.EnableCorrection(corrector);
    }
  }

  virtual Spans Syllabify(const Phrase* phrase);
  size_t BuildSyllableGraph(Prism& prism);
  size_t UseAbbreviatedGraph(ScriptSyllabifier& primary);
  bool HasSpelling(const Code& code,
                   size_t end,
                   bool abbreviated = false,
                   bool full_spelling_only = false) const;
  string GetPreeditString(const Phrase& cand) const;
  string GetOriginalSpelling(const Phrase& cand) const;
  bool IsCorrection(const Code& code, size_t code_length) const;
  bool IsVowellessSingleCharSyllableInput(const Code& code, size_t end) const;

  const SyllableGraph& syllable_graph() const { return syllable_graph_; }

 protected:
  ScriptTranslator* translator_;
  string input_;
  size_t start_;
  Syllabifier syllabifier_;
  SyllableGraph syllable_graph_;
  SyllableGraph abbreviated_graph_;
};

class ScriptTranslation : public Translation {
 public:
  ScriptTranslation(ScriptTranslator* translator,
                    Corrector* corrector,
                    Poet* poet,
                    const string& input,
                    size_t start,
                    size_t end_of_input,
                    int max_sentences,
                    double sentence_cutoff_threshold,
                    bool weight_ordered = false)
      : translator_(translator),
        poet_(poet),
        start_(start),
        end_of_input_(end_of_input),
        syllabifier_(
            New<ScriptSyllabifier>(translator, corrector, input, start)),
        enable_correction_(corrector),
        max_sentences_(max_sentences),
        sentence_cutoff_threshold_(sentence_cutoff_threshold),
        weight_ordered_(weight_ordered) {
    set_exhausted(true);
  }
  bool Evaluate(Dictionary* dict,
                UserDictionary* user_dict,
                an<ScriptSyllabifier> primary = nullptr);
  an<ScriptSyllabifier> syllabifier() const { return syllabifier_; }
  int Compare(an<Translation> other, const CandidateList& candidates) override;
  bool Next() override;
  an<Candidate> Peek() override;

 protected:
  bool CheckEmpty();
  bool IsNormalSpelling() const;
  bool PrepareCandidate();
  template <class QueryResult>
  void EnrollEntries(map<int, DictEntryList>& entries_by_end_pos,
                     const an<QueryResult>& query_result);
  WordGraph PrepareForMakingSentence(Dictionary* dict,
                                     UserDictionary* user_dict);
  an<Sentence> MakeSentence(Dictionary* dict, UserDictionary* user_dict);
  deque<an<Sentence>> MakeSentences(Dictionary* dict,
                                    UserDictionary* user_dict);

  ScriptTranslator* translator_;
  Poet* poet_;
  size_t start_;
  size_t end_of_input_;
  an<ScriptSyllabifier> syllabifier_;

  an<DictEntryCollector> phrase_;
  an<UserDictEntryCollector> user_phrase_;
  deque<an<Sentence>> sentences_;

  an<Phrase> candidate_ = nullptr;
  size_t candidate_index_ = 0;
  enum CandidateSource {
    kUninitialized,
    kUserPhrase,
    kSysPhrase,
    kSentence,
  };
  CandidateSource candidate_source_ = kUninitialized;

  DictEntryCollector::reverse_iterator phrase_iter_;
  UserDictEntryCollector::reverse_iterator user_phrase_iter_;

  const size_t max_corrections_ = 4;
  size_t correction_count_ = 0;
  int max_sentences_ = 1;
  double sentence_cutoff_threshold_ = 0.1;
  bool weight_ordered_ = false;
  size_t elected_code_length_ = 0;

  bool enable_correction_;
};

// ScriptTranslator implementation

ScriptTranslator::ScriptTranslator(const Ticket& ticket)
    : Translator(ticket), Memory(ticket), TranslatorOptions(ticket) {
  if (!engine_)
    return;
  if (Config* config = engine_->schema()->config()) {
    config->GetInt(name_space_ + "/spelling_hints", &spelling_hints_);
    config->GetInt(name_space_ + "/max_word_length", &max_word_length_);
    config->GetBool(name_space_ + "/always_show_comments",
                    &always_show_comments_);
    config->GetBool(name_space_ + "/enable_correction", &enable_correction_);
    if (!config->GetBool(name_space_ + "/enable_word_completion",
                         &enable_word_completion_)) {
      enable_word_completion_ = enable_completion_;
    }
    config->GetInt(name_space_ + "/max_homophones", &max_homophones_);
    poet_.reset(new Poet(language(), config));
  }
  if (enable_correction_) {
    if (auto* corrector = Corrector::Require("corrector")) {
      corrector_.reset(corrector->Create(ticket));
    }
  }
  if (engine_ && engine_->context()) {
    // When BackSpace is unhandled by Rime (i.e. input buffer is already empty and
    // user presses BackSpace to delete committed text in editor), clear inter-commit
    // phrase history to prevent invalid phrase consolidation across deleted text.
    pending_key_connection_ = engine_->context()->unhandled_key_notifier().connect(
        [this](Context* ctx, const KeyEvent& key) {
          if (key.keycode() == XK_BackSpace) {
            last_commit_phrases_.clear();
          }
        });
  }
}

ScriptTranslator::~ScriptTranslator() {
  pending_key_connection_.disconnect();
}

an<Translation> ScriptTranslator::Query(const string& input,
                                        const Segment& segment) {
  if (!dict_ || !dict_->loaded())
    return nullptr;
  if (!segment.HasAnyTagIn(tags_))
    return nullptr;
  DLOG(INFO) << "input = '" << input << "', [" << segment.start << ", "
             << segment.end << ")";

  FinishSession();

  bool enable_user_dict =
      user_dict_ && user_dict_->loaded() && !IsUserDictDisabledFor(input);

  int max_sentences = max_sentences_;
  bool weight_ordered = false;
  if (auto* config = engine_->schema()->config()) {
    bool enable_sentence = true;
    if (config->GetBool(name_space_ + "/enable_sentence", &enable_sentence) && !enable_sentence) {
      max_sentences = 0;
    }
    config->GetBool(name_space_ + "/weight_ordered", &weight_ordered);
  }

  size_t end_of_input = engine_->context()->input().length();
  // the translator should survive translations it creates
  auto result = New<ScriptTranslation>(
      this, corrector_.get(), poet_.get(), input, segment.start, end_of_input,
      max_sentences, sentence_cutoff_threshold_, weight_ordered);
  if (!result || !result->Evaluate(
                     dict_.get(), enable_user_dict ? user_dict_.get() : NULL)) {
    return nullptr;
  }
  an<Translation> translation = result;
  if (result->syllabifier()->syllable_graph().pruned_abbreviations) {
    auto supplement = New<ScriptTranslation>(
        this, nullptr, poet_.get(), input, segment.start, end_of_input, 0, 0.0,
        weight_ordered);
    if (supplement->Evaluate(dict_.get(),
                             enable_user_dict ? user_dict_.get() : nullptr,
                             result->syllabifier())) {
      // Compare uses candidate length, learned status and quality; it does not
      // depend on the menu history. Keep the referenced list alive.
      static const CandidateList empty_candidates;
      auto merged = New<MergedTranslation>(empty_candidates);
      *merged += result;
      *merged += supplement;
      translation = merged;
    }
  }
  auto deduped = New<DistinctTranslation>(translation);
  if (contextual_suggestions_) {
    return poet_->ContextualWeighted(deduped, input, segment.start, this);
  }
  return deduped;
}

static bool exceed_upperlimit(int length, int upper_limit) {
  return upper_limit > 0 && length > upper_limit;
}

bool ScriptTranslator::SaveCommitEntry(CommitEntry& commit_entry) {
  if (exceed_upperlimit(commit_entry.Length(), max_word_length())) {
    UpdateElements(commit_entry);
  } else {
    commit_entry.Save();
  }
  return true;
}

static bool is_pure_cjk_phrase(const std::string& str, size_t* out_char_count = nullptr) {
  if (str.empty()) return false;
  size_t char_count = 0;
  const unsigned char* p = reinterpret_cast<const unsigned char*>(str.data());
  const unsigned char* end = p + str.size();
  while (p < end) {
    if (*p < 0x80) {
      return false; // ASCII (English, digit, symbol)
    } else if ((*p & 0xE0) == 0xC0) {
      return false; // 2-byte UTF-8
    } else if ((*p & 0xF0) == 0xE0) {
      if (p + 3 > end) return false;
      uint32_t cp = ((p[0] & 0x0F) << 12) | ((p[1] & 0x3F) << 6) | (p[2] & 0x3F);
      // CJK Unified Ideographs: 0x4E00 - 0x9FFF, CJK Extension A: 0x3400 - 0x4DBF, CJK symbol 〇: 0x3007
      if (!((cp >= 0x4E00 && cp <= 0x9FFF) || (cp >= 0x3400 && cp <= 0x4DBF) || cp == 0x3007)) {
        return false;
      }
      p += 3;
      ++char_count;
    } else if ((*p & 0xF8) == 0xF0) {
      if (p + 4 > end) return false;
      uint32_t cp = ((p[0] & 0x07) << 18) | ((p[1] & 0x3F) << 12) | ((p[2] & 0x3F) << 6) | (p[3] & 0x3F);
      if (cp < 0x20000 || cp > 0x323AF) {
        return false;
      }
      p += 4;
      ++char_count;
    } else {
      return false;
    }
  }
  if (out_char_count) {
    *out_char_count = char_count;
  }
  // yagni: 2-6 char limits for pure CJK phrases; upgrade when multi-character or mixed consolidation is desired.
  return char_count >= 2 && char_count <= 6;
}

// Called by Memory::OnCommit for each committed segment in the composition.
// Returning true marks the segment as handled by this translator. Phrases are
// queued and consolidated once the final segment is reached.
bool ScriptTranslator::ProcessSegmentOnCommit(CommitEntry& commit_entry,
                                              const Segment& seg) {
  auto phrase =
      As<Phrase>(Candidate::GetGenuineCandidate(seg.GetSelectedCandidate()));
  bool recognized = Language::intelligible(phrase, this);
  if (recognized) {
    queue_.push_back(phrase);
  }

  bool is_last_segment = (engine_ && engine_->context())
      ? (&seg == &engine_->context()->composition().back())
      : true;

  if (!recognized || is_last_segment) {
    if (!queue_.empty()) {
      // 1. Maintain individual word frequencies
      for (const auto& p : queue_) {
        CommitEntry single(this);
        single.AppendPhrase(p);
        SaveCommitEntry(single);
      }

      // 2. Intra-composition consolidation (e.g. 空间 + 基金 in one commit)
      if (queue_.size() > 1) {
        CommitEntry compound(this);
        for (const auto& p : queue_) {
          compound.AppendPhrase(p);
        }
        size_t char_count = 0;
        if (is_pure_cjk_phrase(compound.text, &char_count)) {
          DLOG(INFO) << "Intra-commit consolidation: " << compound.text;
          SaveCommitEntry(compound);
        }
      }

      // 3. Inter-commit flow consolidation (e.g. 空间 commit -> 基金 commit within 3000ms)
      auto now = std::chrono::steady_clock::now();
      if (!last_commit_phrases_.empty()) {
        auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
            now - last_commit_time_).count();
        // yagni: 3000ms inter-commit interval; upgrade when user feedback indicates false-positive consolidations or configurable window is required.
        if (elapsed <= 3000) {
          const auto& prev = last_commit_phrases_.back();
          const auto& curr = queue_.front();
          string combined_text = prev->text() + curr->text();
          size_t combined_count = 0;
          if (is_pure_cjk_phrase(combined_text, &combined_count)) {
            CommitEntry flow_entry(this);
            flow_entry.AppendPhrase(prev);
            flow_entry.AppendPhrase(curr);
            SaveCommitEntry(flow_entry);
            DLOG(INFO) << "Inter-commit consolidation: " << flow_entry.text;
          }
        }
      }

      // Update state for next commit
      size_t count = 0;
      if (is_pure_cjk_phrase(queue_.back()->text(), &count)) {
        last_commit_phrases_ = queue_;
        last_commit_time_ = now;
      } else {
        last_commit_phrases_.clear();
      }
    } else {
      last_commit_phrases_.clear();
    }
    queue_.clear();
  }

  return true;
}

string ScriptTranslator::FormatPreedit(const string& preedit) {
  string result = preedit;
  preedit_formatter_.Apply(&result);
  return result;
}

string ScriptTranslator::Spell(const Code& code) {
  string result;
  vector<string> syllables;
  if (!dict_ || !dict_->Decode(code, &syllables) || syllables.empty())
    return result;
  result = boost::algorithm::join(syllables, string(1, delimiters_.at(0)));
  comment_formatter_.Apply(&result);
  return result;
}

string ScriptTranslator::GetPrecedingText(size_t start) const {
  return !contextual_suggestions_ ? string()
         : start > 0 ? engine_->context()->composition().GetTextBefore(start)
                     : engine_->context()->commit_history().latest_text();
}

bool ScriptTranslator::UpdateElements(const CommitEntry& commit_entry) {
  bool update_elements = false;
  // avoid updating single character entries within a phrase which is
  // composed with single characters only
  if (commit_entry.elements.size() > 1) {
    for (const DictEntry* e : commit_entry.elements) {
      if (e->code.size() > 1) {
        update_elements = true;
        break;
      }
    }
  }
  if (update_elements) {
    for (const DictEntry* e : commit_entry.elements) {
      user_dict_->UpdateEntry(*e, 0);
    }
  }
  return true;
}

bool ScriptTranslator::Memorize(const CommitEntry& commit_entry) {
  UpdateElements(commit_entry);
  user_dict_->UpdateEntry(commit_entry, 1);
  return true;
}

// ScriptSyllabifier implementation

Spans ScriptSyllabifier::Syllabify(const Phrase* phrase) {
  Spans result;
  vector<size_t> vertices;
  vertices.push_back(start_);
  SyllabifyTask task{
      phrase->code(), syllable_graph_, phrase->end() - start_,
      [&](SyllabifyTask* task, size_t depth, size_t current_pos,
          size_t next_pos) { vertices.push_back(start_ + next_pos); },
      [&](SyllabifyTask* task, size_t depth) { vertices.pop_back(); }};
  if (syllabify_dfs(&task, 0, phrase->start() - start_)) {
    result.set_vertices(std::move(vertices));
  }
  return result;
}

size_t ScriptSyllabifier::BuildSyllableGraph(Prism& prism) {
  return (size_t)syllabifier_.BuildSyllableGraph(
      input_, prism, &syllable_graph_, &abbreviated_graph_);
}

size_t ScriptSyllabifier::UseAbbreviatedGraph(ScriptSyllabifier& primary) {
  syllable_graph_ = std::move(primary.abbreviated_graph_);
  return syllable_graph_.interpreted_length;
}

bool ScriptSyllabifier::HasSpelling(const Code& code,
                                    size_t end,
                                    bool abbreviated,
                                    bool full_spelling_only) const {
  set<pair<pair<size_t, size_t>, bool>> visited;
  auto visit = [&](auto&& self, size_t depth, size_t pos,
                   bool has_abbreviation) -> bool {
    if (!visited.emplace(std::make_pair(depth, pos), has_abbreviation).second)
      return false;
    if (depth == code.size())
      return pos == end && (!abbreviated || has_abbreviation);
    auto start = syllable_graph_.edges.find(pos);
    if (start == syllable_graph_.edges.end())
      return false;
    for (const auto& edge : start->second) {
      if (edge.first > end)
        break;
      auto spelling = edge.second.find(code[depth]);
      if (spelling != edge.second.end() &&
          (!full_spelling_only || spelling->second.type <= kFuzzySpelling) &&
          self(self, depth + 1, edge.first,
               has_abbreviation || spelling->second.type == kAbbreviation))
        return true;
    }
    return false;
  };
  return visit(visit, 0, 0, false);
}

bool ScriptSyllabifier::IsCorrection(const Code& code,
                                     size_t code_length) const {
  vector<bool> path_attributes;
  path_attributes.reserve(8);

  SyllabifyTask task{
      code, syllable_graph_, code_length,
      // push
      [&](SyllabifyTask* task, size_t depth, size_t current_pos,
          size_t next_pos) {
        auto id = task->code[depth];

        auto start_iter = syllable_graph_.edges.find(current_pos);
        if (start_iter != syllable_graph_.edges.end()) {
          auto end_iter = start_iter->second.find(next_pos);
          if (end_iter != start_iter->second.end()) {
            auto prop_iter = end_iter->second.find(id);
            if (prop_iter != end_iter->second.end()) {
              path_attributes.push_back(prop_iter->second.is_correction);
              return;
            }
          }
        }
        // edge not found
        path_attributes.push_back(false);
      },
      // pop
      [&](SyllabifyTask* task, size_t depth) { path_attributes.pop_back(); }};

  if (syllabify_dfs(&task, 0, 0)) {
    for (bool is_correction : path_attributes) {
      if (is_correction)
        return true;
    }
  }
  return false;
}

bool ScriptSyllabifier::IsVowellessSingleCharSyllableInput(const Code& code,
                                                           size_t end) const {
  if (code.size() == end && end > 0) {
    for (size_t i = 0; i < end && i < input_.length(); ++i) {
      char c = input_[i];
      if (c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u' || c == 'v') {
        return false;
      }
    }
    return true;
  }
  return false;
}

string ScriptSyllabifier::GetPreeditString(const Phrase& cand) const {
  const auto& delimiters = translator_->delimiters();
  std::stack<size_t> lengths;
  string output;
  SyllabifyTask task{cand.matching_code(), syllable_graph_, cand.end() - start_,
                     [&](SyllabifyTask* task, size_t depth, size_t current_pos,
                         size_t next_pos) {
                       size_t len = output.length();
                       if (depth > 0 && len > 0 &&
                           delimiters.find(output[len - 1]) == string::npos) {
                         output += delimiters.at(0);
                       }
                       output +=
                           input_.substr(current_pos, next_pos - current_pos);
                       lengths.push(len);
                     },
                     [&](SyllabifyTask* task, size_t depth) {
                       output.resize(lengths.top());
                       lengths.pop();
                     }};
  if (syllabify_dfs(&task, 0, cand.start() - start_)) {
    return translator_->FormatPreedit(output);
  } else {
    return string();
  }
}

string ScriptSyllabifier::GetOriginalSpelling(const Phrase& cand) const {
  if (translator_ &&
      static_cast<int>(cand.code().size()) <= translator_->spelling_hints()) {
    return translator_->Spell(cand.code());
  }
  return string();
}

template <class Ptr, class Iter>
static bool has_exact_match_phrase(Ptr ptr, Iter iter, size_t consumed) {
  return ptr && iter->first == consumed && !iter->second.exhausted() &&
         iter->second.Peek()->IsExactMatch();
}

// ScriptTranslation implementation

bool ScriptTranslation::Evaluate(Dictionary* dict,
                                 UserDictionary* user_dict,
                                 an<ScriptSyllabifier> primary) {
  size_t consumed = primary ? syllabifier_->UseAbbreviatedGraph(*primary)
                            : syllabifier_->BuildSyllableGraph(*dict->prism());
  const auto& syllable_graph = syllabifier_->syllable_graph();
  if (primary && consumed != syllable_graph.input_length)
    return false;
  bool predict_word = !primary && translator_->enable_word_completion() &&
                      start_ + consumed == end_of_input_;

  bool full_words_only = (primary != nullptr);
  phrase_ = dict->Lookup(syllable_graph, 0, &translator_->blacklist(),
                         predict_word, 0.0, full_words_only);
  if (user_dict) {
    const size_t kUnlimitedDepth = 0;
    const size_t kNumSyllablesToPredictWord = 4;
    user_phrase_ =
        user_dict->Lookup(syllable_graph, 0, kUnlimitedDepth,
                          predict_word ? kNumSyllablesToPredictWord : 0);
  }
  if (primary) {
    // Supplemental lookup only supplies complete existing words using a newly
    // reachable abbreviation. It never feeds sentence construction.
    auto filter = [primary, expanded = syllabifier_, consumed,
                   matches =
                       map<Code, bool>()](const an<DictEntry>& entry) mutable {
      if (!entry->IsExactMatch())
        return false;
      auto found = matches.find(entry->code);
      if (found != matches.end())
        return found->second;
      bool matches_abbreviation =
          !primary->HasSpelling(entry->code, consumed) &&
          expanded->HasSpelling(entry->code, consumed, true);
      matches.emplace(entry->code, matches_abbreviation);
      return matches_abbreviation;
    };
    auto retain_full_words = [&](auto& collector) {
      if (!collector)
        return;
      for (auto it = collector->begin(); it != collector->end();) {
        if (it->first != syllable_graph.input_length) {
          it = collector->erase(it);
        } else {
          it->second.AddFilter(filter);
          if (it->second.exhausted())
            it = collector->erase(it);
          else
            ++it;
        }
      }
      if (collector->empty())
        collector.reset();
    };
    retain_full_words(phrase_);
    retain_full_words(user_phrase_);
  }
  if (!phrase_ && !user_phrase_)
    return false;

  if (phrase_)
    phrase_iter_ = phrase_->rbegin();
  if (user_phrase_)
    user_phrase_iter_ = user_phrase_->rbegin();

  auto is_correction_match = [&](auto& iter_pair, size_t len) {
    if (iter_pair.first != len || iter_pair.second.exhausted())
      return false;
    return syllabifier_->IsCorrection(iter_pair.second.Peek()->code, len);
  };

  bool has_reliable_phrase =
      has_exact_match_phrase(phrase_, phrase_iter_, consumed) &&
      !is_correction_match(*phrase_iter_, consumed);

  bool has_reliable_user_phrase =
      has_exact_match_phrase(user_phrase_, user_phrase_iter_, consumed) &&
      !is_correction_match(*user_phrase_iter_, consumed);

  bool has_at_least_two_syllables = syllable_graph.edges.size() >= 2;
  DLOG(INFO) << "consumed: " << consumed
             << ", has_reliable_phrase: " << has_reliable_phrase
             << ", has_reliable_user_phrase: " << has_reliable_user_phrase
             << ", has_at_least_two_syllables: " << has_at_least_two_syllables;
  // make sentences when there is no exact-matching phrase candidate
  if (has_at_least_two_syllables && !has_reliable_phrase &&
      !has_reliable_user_phrase) {
    if (max_sentences_ > 1)
      sentences_ = MakeSentences(dict, user_dict);
    else if (max_sentences_) {
      auto sentence = MakeSentence(dict, user_dict);
      if (sentence)
        sentences_ = {sentence};
      else
        sentences_.clear();
    }
  }

  return !CheckEmpty();
}

int ScriptTranslation::Compare(an<Translation> other,
                               const CandidateList& candidates) {
  auto script = dynamic_cast<ScriptTranslation*>(other.get());
  if (script && !exhausted() && !script->exhausted()) {
    auto ours = Peek();
    auto theirs = script->Peek();
    if (ours && theirs && ours->start() == theirs->start() &&
        ours->end() == theirs->end()) {
      // A non-fuzzy abbreviation is not an exact full spelling. Compare the
      // actual paths, rather than treating the primary stream as full spelling.
      if (candidate_ && script->candidate_ && syllabifier_ &&
          script->syllabifier_) {
        bool ours_full = syllabifier_->HasSpelling(
            candidate_->matching_code(), ours->end() - start_, false, true);
        bool theirs_full = script->syllabifier_->HasSpelling(
            script->candidate_->matching_code(),
            theirs->end() - script->start_, false, true);
        if (ours_full == theirs_full &&
            candidate_->entry().is_fuzzy != script->candidate_->entry().is_fuzzy)
          return candidate_->entry().is_fuzzy ? 1 : -1;
        // Prefer complete spellings (including sentences) over abbreviations,
        // independently of fuzziness. Leave learned, partial, completion and
        // correction candidates to the existing ranking rules.
        if (ours_full != theirs_full && ours->type() != "user_phrase" &&
            theirs->type() != "user_phrase" &&
            ours->end() == end_of_input_ &&
            theirs->end() == script->end_of_input_) {
          auto* full = ours_full ? this : script;
          auto* abbreviated = ours_full ? script : this;
          if (full->candidate_->is_exact_match() &&
              !full->syllabifier_->IsCorrection(
                  full->candidate_->matching_code(), ours->end() - full->start_) &&
              !full->syllabifier_->IsVowellessSingleCharSyllableInput(
                  full->candidate_->matching_code(), ours->end() - full->start_) &&
              abbreviated->candidate_->is_exact_match() &&
              abbreviated->syllabifier_->HasSpelling(
                  abbreviated->candidate_->matching_code(),
                  ours->end() - abbreviated->start_, true))
            return ours_full ? -1 : 1;
        }
      }
      bool ours_learned =
          ours->type() == "user_phrase" && candidate_ && syllabifier_ &&
          !syllabifier_->IsCorrection(candidate_->code(), ours->end() - start_);
      bool theirs_learned =
          theirs->type() == "user_phrase" && script->candidate_ &&
          script->syllabifier_ &&
          !script->syllabifier_->IsCorrection(script->candidate_->code(),
                                              theirs->end() - script->start_);
      if (ours_learned != theirs_learned)
        return ours_learned ? -1 : 1;
    }
  }
  return Translation::Compare(other, candidates);
}

bool ScriptTranslation::Next() {
  do {
    if (exhausted())
      return false;
    if (candidate_source_ == kUninitialized) {
      PrepareCandidate();  // to determine candidate_source_
    }
    switch (candidate_source_) {
      case kUninitialized:
        break;
      case kSentence:
        if (!sentences_.empty())
          sentences_.pop_front();
        break;
      case kUserPhrase: {
        if (weight_ordered_) {
          if (user_phrase_) {
            auto it = user_phrase_->find(elected_code_length_);
            if (it != user_phrase_->end()) {
              it->second.Next();
            }
          }
        } else {
          UserDictEntryIterator& uter(user_phrase_iter_->second);
          if (!uter.Next()) {
            ++user_phrase_iter_;
          }
        }
      } break;
      case kSysPhrase: {
        if (weight_ordered_) {
          if (phrase_) {
            auto it = phrase_->find(elected_code_length_);
            if (it != phrase_->end()) {
              it->second.Next();
            }
          }
        } else {
          DictEntryIterator& iter(phrase_iter_->second);
          if (!iter.Next()) {
            ++phrase_iter_;
          }
        }
      } break;
    }
    candidate_.reset();
    candidate_source_ = kUninitialized;
    if (enable_correction_) {
      // populate next candidate and skip it if it's a correction beyond max
      // numbers.
      if (!PrepareCandidate()) {
        break;
      }
    }
  } while (enable_correction_ &&
           syllabifier_->IsCorrection(candidate_->code(),
                                      candidate_->end() - start_) &&
           // limit the number of correction candidates
           ++correction_count_ > max_corrections_);
  if (!CheckEmpty()) {
    ++candidate_index_;
    return true;
  }
  return false;
}

an<Candidate> ScriptTranslation::Peek() {
  if (candidate_source_ == kUninitialized && !PrepareCandidate()) {
    return nullptr;
  }
  if (candidate_->preedit().empty()) {
    candidate_->set_preedit(syllabifier_->GetPreeditString(*candidate_));
  }
  if (candidate_->comment().empty()) {
    auto spelling = syllabifier_->GetOriginalSpelling(*candidate_);
    if (!spelling.empty() && (translator_->always_show_comments() ||
                              spelling != candidate_->preedit())) {
      candidate_->set_comment(/*quote_left + */ spelling /* + quote_right*/);
    }
  }
  candidate_->set_syllabifier(syllabifier_);
  return candidate_;
}

static bool always_true() {
  return true;
}

template <typename T>
inline static bool prefer_user_phrase(
    T user_phrase_weight,
    T sys_phrase_weight,
    function<bool()> compare_on_tie = always_true) {
  return user_phrase_weight > sys_phrase_weight ||
         (user_phrase_weight == sys_phrase_weight && compare_on_tie());
}

bool ScriptTranslation::PrepareCandidate() {
iter_incremented:
  if (exhausted()) {
    candidate_source_ = kUninitialized;
    candidate_ = nullptr;
    return false;
  }
  if (!sentences_.empty()) {
    candidate_source_ = kSentence;
    candidate_ = sentences_[0];
    return true;
  }
  const size_t full_code_length = end_of_input_ - start_;
  if (weight_ordered_) {
    size_t best_user_len = 0;
    double best_user_score = -1e18;
    if (user_phrase_) {
      for (auto& pair : *user_phrase_) {
        if (pair.second.exhausted())
          continue;
        const auto& entry = pair.second.Peek();
        double score = entry->weight + 1.0 * pair.first;
        if (pair.first == full_code_length)
          score += 100.0;
        if (score > best_user_score) {
          best_user_score = score;
          best_user_len = pair.first;
        }
      }
    }
    size_t best_phrase_len = 0;
    double best_phrase_score = -1e18;
    if (phrase_) {
      for (auto& pair : *phrase_) {
        if (pair.second.exhausted())
          continue;
        const auto& entry = pair.second.Peek();
        double score = entry->weight + 1.0 * pair.first;
        if (pair.first == full_code_length)
          score += 100.0;
        if (score > best_phrase_score) {
          best_phrase_score = score;
          best_phrase_len = pair.first;
        }
      }
    }
    if (best_user_len > 0 && (best_phrase_len == 0 || best_user_score >= best_phrase_score)) {
      UserDictEntryIterator& uter = (*user_phrase_)[best_user_len];
      const auto& entry = uter.Peek();
      DLOG(INFO) << "user phrase '" << entry->text
                 << "', code length: " << best_user_len;
      candidate_source_ = kUserPhrase;
      elected_code_length_ = best_user_len;
      candidate_ = New<Phrase>(
          translator_->language(),
          entry->IsPredictiveMatch() ? "completion" : "user_phrase",
          start_, start_ + best_user_len, entry);
      candidate_->set_quality(std::exp(entry->weight) +
                              translator_->initial_quality() +
                              (entry->quality_len / full_code_length));
      return true;
    } else if (best_phrase_len > 0) {
      DictEntryIterator& iter = (*phrase_)[best_phrase_len];
      const auto& entry = iter.Peek();
      DLOG(INFO) << "phrase '" << entry->text
                 << "', code length: " << best_phrase_len;
      candidate_source_ = kSysPhrase;
      elected_code_length_ = best_phrase_len;
      candidate_ = New<Phrase>(
          translator_->language(),
          entry->IsPredictiveMatch() ? "completion" : "phrase",
          start_, start_ + best_phrase_len, entry);
      candidate_->set_quality(std::exp(entry->weight) +
                              translator_->initial_quality() +
                              (entry->quality_len / full_code_length));
      return true;
    } else {
      candidate_source_ = kUninitialized;
      candidate_ = nullptr;
      return false;
    }
  }
  size_t user_phrase_code_length = 0;
  if (user_phrase_ && user_phrase_iter_ != user_phrase_->rend()) {
    user_phrase_code_length = user_phrase_iter_->first;
  }
  size_t phrase_code_length = 0;
  if (phrase_ && phrase_iter_ != phrase_->rend()) {
    phrase_code_length = phrase_iter_->first;
  }
  if (user_phrase_code_length > 0 &&
      prefer_user_phrase(
          user_phrase_code_length, phrase_code_length,
          // Prefer user phrase when code lengths are identical
          [this, full_code_length, phrase_code_length]() {
            UserDictEntryIterator& uter = user_phrase_iter_->second;
            DictEntryIterator& iter = phrase_iter_->second;
            if (uter.Peek()->is_fuzzy != iter.Peek()->is_fuzzy)
              return !uter.Peek()->is_fuzzy;
            // If user phrase is a correction result, waive priority and compare weights
            bool user_is_correction = syllabifier_->IsCorrection(
                uter.Peek()->code, phrase_code_length);
            if (user_is_correction) {
              // Due to heavy penalty on correction candidates, usually system original > user correction
              // When both are corrections, dynamic user weight helps achieve user correction > system correction
              return uter.Peek()->weight >= iter.Peek()->weight;
            }
            // At least one exact match candidate must precede long-phrase association
            // Thus for the top candidate, system exact match > user long-phrase association
            const int kNumExactMatchOnTop = 1;
            return candidate_index_ >= kNumExactMatchOnTop ||
                   prefer_user_phrase(
                       has_exact_match_phrase(user_phrase_, user_phrase_iter_,
                                              full_code_length),
                       has_exact_match_phrase(phrase_, phrase_iter_,
                                              full_code_length));
          })) {
    UserDictEntryIterator& uter = user_phrase_iter_->second;
    const auto& entry = uter.Peek();
    DLOG(INFO) << "user phrase '" << entry->text
               << "', code length: " << user_phrase_code_length;
    candidate_source_ = kUserPhrase;
    candidate_ =
        New<Phrase>(translator_->language(),
                    entry->IsPredictiveMatch() ? "completion" : "user_phrase",
                    start_, start_ + user_phrase_code_length, entry);
    candidate_->set_quality(std::exp(entry->weight) +
                            translator_->initial_quality() +
                            (entry->quality_len / full_code_length));
    return true;
  } else if (phrase_code_length > 0) {
    DictEntryIterator& iter = phrase_iter_->second;
    if (iter.exhausted()) {
      ++phrase_iter_;
      goto iter_incremented;
    }
    const auto& entry = iter.Peek();
    DLOG(INFO) << "phrase '" << entry->text
               << "', code length: " << phrase_code_length;
    candidate_source_ = kSysPhrase;
    candidate_ =
        New<Phrase>(translator_->language(),
                    entry->IsPredictiveMatch() ? "completion" : "phrase",
                    start_, start_ + phrase_code_length, entry);
    candidate_->set_quality(std::exp(entry->weight) +
                            translator_->initial_quality() +
                            (entry->quality_len / full_code_length));
    return true;
  } else {
    candidate_source_ = kUninitialized;
    candidate_ = nullptr;
    return false;
  }
}

bool ScriptTranslation::CheckEmpty() {
  if (weight_ordered_) {
    bool phrase_empty = true;
    if (phrase_) {
      for (auto& pair : *phrase_) {
        if (!pair.second.exhausted()) {
          phrase_empty = false;
          break;
        }
      }
    }
    bool user_phrase_empty = true;
    if (user_phrase_) {
      for (auto& pair : *user_phrase_) {
        if (!pair.second.exhausted()) {
          user_phrase_empty = false;
          break;
        }
      }
    }
    set_exhausted(sentences_.empty() && phrase_empty && user_phrase_empty);
    return exhausted();
  }
  set_exhausted((!phrase_ || phrase_iter_ == phrase_->rend()) &&
                (!user_phrase_ || user_phrase_iter_ == user_phrase_->rend()));
  return exhausted();
}

template <class QueryResult>
void ScriptTranslation::EnrollEntries(
    map<int, DictEntryList>& entries_by_end_pos,
    const an<QueryResult>& query_result) {
  if (query_result) {
    for (auto& y : *query_result) {
      DictEntryList& homophones = entries_by_end_pos[y.first];
      while (homophones.size() < translator_->max_homophones() &&
             !y.second.exhausted()) {
        homophones.push_back(y.second.Peek());
        if (!y.second.Next())
          break;
      }
    }
  }
}

WordGraph ScriptTranslation::PrepareForMakingSentence(
    Dictionary* dict,
    UserDictionary* user_dict) {
  const int kMaxSyllablesForUserPhraseQuery = 5;
  const auto& syllable_graph = syllabifier_->syllable_graph();
  WordGraph graph;
  for (const auto& x : syllable_graph.edges) {
    auto& same_start_pos = graph[x.first];
    if (user_dict) {
      EnrollEntries(same_start_pos,
                    user_dict->Lookup(syllable_graph, x.first,
                                      kMaxSyllablesForUserPhraseQuery));
    }
    // merge lookup results
    EnrollEntries(same_start_pos, dict->Lookup(syllable_graph, x.first,
                                               &translator_->blacklist()));
  }
  return graph;
}

deque<an<Sentence>> ScriptTranslation::MakeSentences(
    Dictionary* dict,
    UserDictionary* user_dict) {
  const auto& syllable_graph = syllabifier_->syllable_graph();
  WordGraph graph = PrepareForMakingSentence(dict, user_dict);
  auto sentences =
      poet_->MakeSentences(graph, syllable_graph.interpreted_length,
                           translator_->GetPrecedingText(start_),
                           max_sentences_, sentence_cutoff_threshold_);
  for (auto& sentence : sentences) {
    sentence->Offset(start_);
    sentence->set_syllabifier(syllabifier_);
  }
  return sentences;
}

an<Sentence> ScriptTranslation::MakeSentence(Dictionary* dict,
                                             UserDictionary* user_dict) {
  const auto& syllable_graph = syllabifier_->syllable_graph();
  WordGraph graph = PrepareForMakingSentence(dict, user_dict);
  if (auto sentence =
          poet_->MakeSentence(graph, syllable_graph.interpreted_length,
                              translator_->GetPrecedingText(start_))) {
    sentence->Offset(start_);
    sentence->set_syllabifier(syllabifier_);
    return sentence;
  }
  return nullptr;
}

}  // namespace rime
