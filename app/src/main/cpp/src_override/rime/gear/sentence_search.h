#ifndef RIME_SENTENCE_SEARCH_H_
#define RIME_SENTENCE_SEARCH_H_

#include <rime/dict/lookup_budget.h>
#include <rime/gear/poet.h>

namespace rime {

struct SentenceSearchBudget {
  LookupBudget system_lookup{8192, 4096};
  LookupBudget user_lookup{4096, 2048};
  size_t positions_remaining = 48;
  size_t graph_entries_remaining = 768;
  size_t candidate_scans_remaining = 8192;
  size_t expansions_remaining = 16384;
};

double SentenceWordScore(const DictEntry& entry);
double SentenceScoreToQuality(double score);
bool MergeSentenceEntry(DictEntryList& entries, const an<DictEntry>& entry);

void LimitSentenceCandidates(deque<an<Sentence>>& sentences,
                             size_t max_sentences, double cutoff_threshold,
                             const an<Sentence>& protected_sentence = nullptr);

deque<an<Sentence>> SearchSentences(const WordGraph& graph, size_t total_length,
                                    const Language* language, size_t capacity,
                                    SentenceSearchBudget& budget);

}

#endif
