#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <iostream>
#include <random>
#include <rime/composition.h>
#include <rime/gear/sentence_search.h>

using namespace rime;

void Require(bool condition, const char* message) {
  if (!condition) {
    std::cerr << message << '\n';
    std::exit(1);
  }
}

an<DictEntry> Entry(const string& text, std::initializer_list<int> code,
                    double weight) {
  auto entry = New<DictEntry>();
  entry->text = text;
  entry->code.assign(code.begin(), code.end());
  entry->weight = weight;
  return entry;
}

struct Expected {
  string text;
  Code code;
  double score;
};

void TestSentenceEntryMerge() {
  DictEntryList choices;
  auto learned = Entry("learned", {1}, -6.0);
  learned->commit_count = 9;
  auto system = Entry("system", {2}, -4.0);
  Require(MergeSentenceEntry(choices, learned) &&
              MergeSentenceEntry(choices, system) && choices.size() == 2,
          "Different source readings did not retain independent entries");
  auto stronger = Entry("learned", {1}, -3.0);
  Require(!MergeSentenceEntry(choices, stronger) && choices.size() == 2 &&
              choices.front() == stronger,
          "Duplicate graph entries lost the stronger score or consumed capacity");
  Require(!MergeSentenceEntry(choices, learned) && choices.front() == stronger,
          "A weaker duplicate replaced the retained entry");
  auto alternative_reading = Entry("learned", {3}, -5.0);
  Require(MergeSentenceEntry(choices, alternative_reading) && choices.size() == 3,
          "Different readings of the same text were incorrectly deduplicated");
  auto fuzzy = Entry("learned", {1}, 0.0);
  fuzzy->is_fuzzy = true;
  Require(!MergeSentenceEntry(choices, fuzzy) && choices.front() == stronger,
          "A fuzzy duplicate replaced a normal spelling");
  auto completion = Entry("completion", {1, 2}, 0.0);
  completion->matching_code_size = 1;
  Require(!MergeSentenceEntry(choices, completion) &&
              !MergeSentenceEntry(choices, nullptr) && choices.size() == 3,
          "Invalid or predictive entries entered the sentence graph");
}

void Enumerate(const WordGraph& graph, size_t position, size_t end,
               Expected prefix, map<pair<string, Code>, double>& results) {
  if (position == end) {
    auto key = std::make_pair(prefix.text, prefix.code);
    auto previous = results.find(key);
    if (previous == results.end() || previous->second < prefix.score)
      results[key] = prefix.score;
    return;
  }
  auto vertex = graph.find(position);
  if (vertex == graph.end())
    return;
  for (const auto& edge : vertex->second) {
    if (edge.first <= position || edge.first > end ||
        (position == 0 && edge.first == end))
      continue;
    for (const auto& entry : edge.second) {
      auto next = prefix;
      next.text += entry->text;
      next.code.insert(next.code.end(), entry->code.begin(), entry->code.end());
      next.score += SentenceWordScore(*entry);
      Enumerate(graph, edge.first, end, std::move(next), results);
    }
  }
}

void CheckAgainstEnumeration(const WordGraph& graph, size_t end,
                             size_t capacity) {
  map<pair<string, Code>, double> expected;
  Enumerate(graph, 0, end, {{}, {}, 0.0}, expected);
  vector<double> scores;
  for (const auto& path : expected) scores.push_back(path.second);
  std::sort(scores.begin(), scores.end(), std::greater<double>());
  SentenceSearchBudget budget;
  auto sentences = SearchSentences(graph, end, nullptr, capacity, budget);
  Require(sentences.size() == std::min(capacity, scores.size()),
          "Search did not retain the expected number of paths");
  set<pair<string, Code>> seen;
  size_t index = 0;
  for (const auto& sentence : sentences) {
    auto key = std::make_pair(sentence->text(), sentence->code());
    auto found = expected.find(key);
    Require(found != expected.end(), "Search returned an unreachable path");
    Require(seen.insert(key).second, "Search returned a duplicate explanation");
    Require(std::abs(sentence->weight() - scores[index++]) < 1e-10,
            "Search differs from exhaustive top K scores");
    Require(std::abs(sentence->weight() - found->second) < 1e-10,
            "Search discarded a better duplicate explanation");
    Require(sentence->size() > 1 && sentence->end() == end,
            "Search lost the native sentence structure");
    Require(sentence->components().size() == sentence->word_lengths().size(),
            "Sentence components and spans disagree");
  }
}

void TestCompleteWordAndSharedPrefix() {
  WordGraph graph;
  graph[0][2].push_back(Entry("direct", {9, 9}, 100.0));
  graph[0][1].push_back(Entry("a", {1}, 3.0));
  for (int index = 0; index < 12; ++index)
    graph[1][2].push_back(Entry(std::to_string(index), {index + 2}, index));
  CheckAgainstEnumeration(graph, 2, 12);
  Require(graph[0][2][0]->weight == 100.0,
          "Search mutated the original dictionary entry");
}

void TestDuplicateAndReadingIdentity() {
  WordGraph graph;
  graph[0][1].push_back(Entry("a", {1}, 8.0));
  graph[0][1].push_back(Entry("a", {2}, 7.0));
  graph[1][2].push_back(Entry("b", {3}, 8.0));
  graph[0][2].push_back(Entry("ab", {1, 3}, -20.0));
  graph[2][3].push_back(Entry("c", {4}, 5.0));
  CheckAgainstEnumeration(graph, 3, 8);
}

void TestBudgetsAndInvalidEdges() {
  WordGraph graph;
  graph[0][0].push_back(Entry("loop", {1}, 100.0));
  graph[0][1].push_back(Entry("a", {1}, 1.0));
  graph[1][0].push_back(Entry("backward", {1}, 100.0));
  graph[1][2].push_back(Entry("b", {2}, 2.0));
  graph[1][3].push_back(Entry("overflow", {3}, 100.0));
  for (size_t remaining : {size_t{0}, size_t{1}, size_t{3}, size_t{8}}) {
    SentenceSearchBudget budget;
    budget.expansions_remaining = remaining;
    SearchSentences(graph, 2, nullptr, 8, budget);
    Require(budget.expansions_remaining <= remaining,
            "Search exceeded or replenished its expansion budget");
  }
  CheckAgainstEnumeration(graph, 2, 8);
  LookupBudget lookup{1, 2};
  Require(lookup.Traverse() && !lookup.Traverse(),
          "Traversal budget was not enforced");
  Require(lookup.ReadEntry() && lookup.ReadEntry() && !lookup.ReadEntry(),
          "Entry budget was not enforced");
}

void TestCompositionRevisions() {
  Composition composition;
  composition.Reset("same");
  const auto first = composition.revision();
  composition.Reset("same");
  Require(first != composition.revision(),
          "Identical input reused its previous composition revision");
  const auto second = composition.revision();
  composition.Reset(size_t{0});
  Require(second != composition.revision(),
          "Segment reset reused its previous composition revision");
  Composition other;
  other.Reset("same");
  Require(other.revision() != composition.revision(),
          "Independent compositions shared a revision");
}

void TestDefaultLookupBudget() {
  LookupBudget budget;
  Require(!budget.traversal_remaining && !budget.entries_remaining &&
              !budget.Traverse() && !budget.ReadEntry(),
          "Default lookup budget was not exhausted");
  auto share = budget.Share(0);
  Require(!share.traversal_remaining && !share.entries_remaining,
          "Zero remaining positions created a lookup allowance");
}

void TestIndependentLookupShares() {
  for (size_t positions = 1; positions <= 48; ++positions) {
    SentenceSearchBudget budget;
    for (size_t remaining = positions; remaining; --remaining) {
      const auto system_before = budget.system_lookup;
      const auto user_share = budget.user_lookup.Share(remaining);
      Require(user_share.traversal_remaining && user_share.entries_remaining,
              "User lookup starved a tail position");
      budget.user_lookup.traversal_remaining -= user_share.traversal_remaining;
      budget.user_lookup.entries_remaining -= user_share.entries_remaining;
      Require(budget.system_lookup.traversal_remaining ==
                  system_before.traversal_remaining &&
                  budget.system_lookup.entries_remaining ==
                      system_before.entries_remaining,
              "User lookup consumed the system lookup budget");
      const auto system_share = budget.system_lookup.Share(remaining);
      Require(system_share.traversal_remaining && system_share.entries_remaining,
              "System lookup starved a tail position");
      budget.system_lookup.traversal_remaining -=
          system_share.traversal_remaining;
      budget.system_lookup.entries_remaining -= system_share.entries_remaining;
    }
    Require(!budget.system_lookup.traversal_remaining &&
                !budget.system_lookup.entries_remaining &&
                !budget.user_lookup.traversal_remaining &&
                !budget.user_lookup.entries_remaining,
            "Lookup shares lost or replenished budget");
  }
}

void TestSentenceScoreQuality() {
  const double word_base_score = std::log(1e-6);
  auto entry = Entry("word", {1}, 3.0);
  const double score = SentenceWordScore(*entry);
  Require(std::abs(score - (entry->weight + word_base_score)) < 1e-12,
          "Word score no longer follows the upstream additive contract");
  Require(std::abs(SentenceScoreToQuality(score) - std::exp(entry->weight)) <
              1e-10,
          "Word and sentence quality use different base costs");
  for (size_t words : {size_t{2}, size_t{3}, size_t{4}}) {
    const double sentence_score = score * words;
    const double expected =
        std::exp(entry->weight * words + word_base_score * (words - 1));
    Require(std::abs(SentenceScoreToQuality(sentence_score) / expected - 1.0) <
                1e-10,
            "Sentence quality changed the existing per-word cost");
    Require(SentenceScoreToQuality(sentence_score) < SentenceScoreToQuality(score),
            "Quality reversed the additive score ordering");
  }
}

void TestSearchWorkBudget() {
  WordGraph graph;
  graph[0][1].push_back(Entry("a", {1}, 1.0));
  graph[1][2].push_back(Entry("b", {2}, 1.0));
  for (size_t operations = 0; operations <= 6; ++operations) {
    SentenceSearchBudget budget;
    budget.expansions_remaining = operations;
    auto sentences = SearchSentences(graph, 2, nullptr, 8, budget);
    Require(sentences.size() == (operations >= 4 ? 1 : 0),
            "Search did not charge seed and materialization work separately");
    Require(budget.expansions_remaining == (operations >= 4 ? operations - 4 : 0),
            "Empty queue or failed budget check consumed search work");
  }
}

an<Sentence> SentenceCandidate(const string& text, double weight) {
  auto sentence = New<Sentence>(nullptr);
  sentence->Extend(*Entry(text, {1}, 0.0), 1, weight);
  return sentence;
}

void TestSentenceCandidateLimits() {
  auto original = SentenceCandidate("original", -100.0);
  for (size_t limit : {size_t{0}, size_t{1}, size_t{2}}) {
    deque<an<Sentence>> sentences = {SentenceCandidate("a", -101.0),
                                    SentenceCandidate("b", -102.0),
                                    SentenceCandidate("c", -103.0)};
    LimitSentenceCandidates(sentences, limit, 1.0);
    Require(sentences.size() == limit, "Sentence output ignored the configured limit");
    LimitSentenceCandidates(sentences, limit, 1.0, original);
    Require(sentences.size() == (limit ? limit - 1 : 0),
            "Protected sentence was not included in the output limit");
  }
  deque<an<Sentence>> sentences = {SentenceCandidate("original", -90.0),
                                  SentenceCandidate("near", -105.0),
                                  SentenceCandidate("far", -130.0)};
  LimitSentenceCandidates(sentences, 3, 0.1, original);
  Require(sentences.size() == 1 && sentences.front()->text() == "near" &&
              original->text() == "original" && original->weight() == -100.0,
          "Protected head, duplicate filtering or relative cutoff changed");
  sentences = {SentenceCandidate("same", -100.0),
               SentenceCandidate("same", -101.0),
               SentenceCandidate("next", -102.0)};
  LimitSentenceCandidates(sentences, 2, 1.0);
  Require(sentences.size() == 2 && sentences.back()->text() == "next",
          "Duplicate text consumed a sentence output slot");
  sentences = {SentenceCandidate("a", -100.0),
               SentenceCandidate("b", -109.0),
               SentenceCandidate("c", -119.0)};
  LimitSentenceCandidates(sentences, 4, 0.1);
  Require(sentences.size() == 2, "Relative cutoff did not accelerate after acceptance");
}

void TestProtectedSentenceAcceptsBetterCandidates() {
  auto original = SentenceCandidate("original", -100.0);
  deque<an<Sentence>> sentences = {SentenceCandidate("better", -80.0),
                                  SentenceCandidate("near", -83.0),
                                  SentenceCandidate("far", -100.0)};
  LimitSentenceCandidates(sentences, 4, 0.1, original);
  Require(sentences.size() == 2 && sentences.front()->text() == "better" &&
              sentences.back()->text() == "near",
          "Protected head rejected a better sentence or disabled later cutoff");
  Require(original->text() == "original" && original->weight() == -100.0,
          "Accepting a better sentence mutated the protected head");

  sentences = {SentenceCandidate("better", -80.0),
               SentenceCandidate("equal", -80.0),
               SentenceCandidate("worse", -80.01)};
  LimitSentenceCandidates(sentences, 4, 0.0, original);
  Require(sentences.size() == 2 && sentences.front()->text() == "better" &&
              sentences.back()->text() == "equal",
          "Zero cutoff rejected an improvement or accepted a score decrease");

  sentences = {SentenceCandidate("better", -80.0)};
  LimitSentenceCandidates(sentences, 1, 0.1, original);
  Require(sentences.empty(),
          "Better sentence bypassed the protected head output allowance");

  auto zero = SentenceCandidate("zero", 0.0);
  sentences = {SentenceCandidate("positive", 1.0),
               SentenceCandidate("near", 0.99),
               SentenceCandidate("far", 0.8)};
  LimitSentenceCandidates(sentences, 4, 0.1, zero);
  Require(sentences.size() == 2 && sentences.front()->text() == "positive" &&
              sentences.back()->text() == "near",
          "Zero-weight protected head rejected a score improvement");
  sentences = {SentenceCandidate("equal", 0.0),
               SentenceCandidate("negative", -0.01)};
  LimitSentenceCandidates(sentences, 4, 0.1, zero);
  Require(sentences.size() == 1 && sentences.front()->text() == "equal",
          "Zero-weight cutoff changed equality or accepted a score decrease");
}

void TestSentenceCutoffMatchesPoet() {
  WordGraph graph;
  graph[0][1].push_back(Entry("prefix", {1}, 0.0));
  for (int index = 0; index < 5; ++index)
    graph[1][2].push_back(Entry(std::to_string(index), {index + 2}, -index * 2.0));
  Poet poet(nullptr, nullptr);
  for (size_t limit : {size_t{1}, size_t{2}, size_t{4}}) {
    for (double cutoff : {0.0, 0.05, 0.1, 1.0}) {
      SentenceSearchBudget budget;
      auto actual = SearchSentences(graph, 2, nullptr, 64, budget);
      LimitSentenceCandidates(actual, limit, cutoff);
      const auto expected = poet.MakeSentences(graph, 2, "", limit, cutoff);
      Require(actual.size() == expected.size(), "Sentence cutoff differs from Poet");
      for (size_t index = 0; index < actual.size(); ++index)
        Require(actual[index]->text() == expected[index]->text() &&
                    std::abs(actual[index]->weight() - expected[index]->weight()) < 1e-10,
                "Sentence cutoff changed Poet ordering or weights");
    }
  }
}

void TestRandomGraphs() {
  std::mt19937 random(1362026);
  for (size_t repeat = 0; repeat < 200; ++repeat) {
    WordGraph graph;
    const size_t end = 2 + random() % 4;
    for (size_t start = 0; start < end; ++start) {
      for (size_t target = start + 1; target <= end; ++target) {
        for (size_t choice = 0; choice < 1 + random() % 3; ++choice) {
          string text(1, 'a' + random() % 4);
          const double weight = static_cast<int>(random() % 2000) / 101.0;
          graph[start][target].push_back(
              Entry(text, {static_cast<int>(random() % 5)}, weight));
        }
      }
    }
    CheckAgainstEnumeration(graph, end, 1 + random() % 16);
  }
}

int main() {
  TestSentenceEntryMerge();
  TestCompleteWordAndSharedPrefix();
  TestDuplicateAndReadingIdentity();
  TestBudgetsAndInvalidEdges();
  TestCompositionRevisions();
  TestDefaultLookupBudget();
  TestIndependentLookupShares();
  TestSentenceScoreQuality();
  TestSearchWorkBudget();
  TestSentenceCandidateLimits();
  TestProtectedSentenceAcceptsBetterCandidates();
  TestSentenceCutoffMatchesPoet();
  TestRandomGraphs();
  std::cout << "PASS sentence search, configured limits, Poet cutoff, scoring, budget and lifecycle cases\n";
}
