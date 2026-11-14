#include <algorithm>
#include <filesystem>
#include <iostream>
#include <rime_api.h>
#include <rime/context.h>
#include <rime/config.h>
#include <rime/algo/syllabifier.h>
#include <rime/candidate.h>
#include <rime/dict/dictionary.h>
#include <rime/engine.h>
#include <rime/gear/lime_user_dict_writes.h>
#include <rime/gear/script_translator.h>
#include <rime/gear/sentence_search.h>
#include <rime/schema.h>
#include <rime/ticket.h>
#include <rime/menu.h>

using namespace rime;

class BudgetTranslator : public ScriptTranslator {
 public:
  explicit BudgetTranslator(Engine* engine)
      : ScriptTranslator(Ticket(engine, "translator", "script_translator")) {}

  an<SentenceSearchBudget> budget() const { return search_budget_; }
  Dictionary* dictionary() const { return dict_.get(); }
};

void Require(bool condition, const char* message) {
  if (!condition)
    throw std::runtime_error(message);
}

void TestDefaultSentenceLimit() {
  the<Engine> engine(Engine::Create());
  engine->ApplySchema(new Schema("pinyin"));
  auto* context = engine->context();
  context->set_option("ascii_mode", false);
  context->set_option("emoji", false);
  context->set_option("traditionalization", false);
  context->set_input("zgnzmk");
  auto menu = context->composition().back().menu;
  Require(menu != nullptr, "Default sentence fixture has no menu");
  size_t sentences = 0;
  for (size_t index = 0; index < 256; ++index) {
    auto candidate = menu->GetCandidateAt(index);
    if (!candidate)
      break;
    if (candidate->type() == "sentence")
      ++sentences;
  }
  Require(sentences == 1, "Default max_sentences did not include the protected sentence");
}

void TestConfiguredSentenceLimits(int max_sentences, double cutoff, bool enabled) {
  the<Engine> engine(Engine::Create());
  auto* schema = new Schema("pinyin");
  schema->config()->SetInt("translator/max_sentences", max_sentences);
  schema->config()->SetDouble("translator/sentence_cutoff_threshold", cutoff);
  schema->config()->SetBool("translator/enable_sentence", enabled);
  engine->ApplySchema(schema);
  auto* context = engine->context();
  context->set_option("ascii_mode", false);
  context->set_option("emoji", false);
  context->set_option("traditionalization", false);
  context->set_input("zgnzmk");
  auto menu = context->composition().back().menu;
  Require(menu != nullptr, "Configured sentence fixture has no menu");
  size_t sentences = 0;
  for (size_t index = 0; index < 256; ++index) {
    auto candidate = menu->GetCandidateAt(index);
    if (!candidate)
      break;
    if (candidate->type() == "sentence")
      ++sentences;
  }
  const size_t output_limit = enabled ? std::min(std::max(1, max_sentences), 100) : 0;
  Require(sentences <= output_limit,
          "Actual menu bypassed max_sentences or enable_sentence");
  if (output_limit > 0) {
    Require(menu->GetCandidateAt(0)->text() ==
                u8"\u8fd9\u4e2a\u4f60\u600e\u4e48\u770b",
            "Configured limit replaced the protected sentence head");
    if (cutoff >= 1.0 && output_limit <= 3)
      Require(sentences == output_limit,
              "Configured multi-sentence search did not fill its allowance");
  }
}

int main(int argc, char** argv) {
  if (argc != 3)
    return 2;
  const string shared = std::filesystem::absolute(argv[1]).string();
  const string user = std::filesystem::absolute(argv[2]).string();
  const string prebuilt = shared + "/build";
  const string staging = user + "/build";
  std::filesystem::create_directories(staging);
  auto* api = rime_get_api();
  RIME_STRUCT(RimeTraits, traits);
  traits.shared_data_dir = shared.c_str();
  traits.user_data_dir = user.c_str();
  traits.prebuilt_data_dir = prebuilt.c_str();
  traits.staging_dir = staging.c_str();
  traits.app_name = "rime.lime_engine_regression";
  traits.min_log_level = 3;
  traits.log_dir = user.c_str();
  api->setup(&traits);
  api->initialize(&traits);
  TestDefaultSentenceLimit();
  TestConfiguredSentenceLimits(0, 1.0, true);
  TestConfiguredSentenceLimits(1, 1.0, true);
  TestConfiguredSentenceLimits(3, 1.0, true);
  TestConfiguredSentenceLimits(8, 0.0, true);
  TestConfiguredSentenceLimits(8, 0.1, true);
  TestConfiguredSentenceLimits(3, 1.0, false);
  {
    the<Engine> engine(Engine::Create());
    auto* schema = new Schema("pinyin");
    schema->config()->SetInt("translator/max_sentences", 64);
    schema->config()->SetDouble("translator/sentence_cutoff_threshold", 1.0);
    schema->config()->SetBool("translator/enable_sentence", true);
    engine->ApplySchema(schema);
    auto* context = engine->context();
    context->set_option("ascii_mode", false);
    context->set_option("emoji", false);
    context->set_option("traditionalization", false);
    context->set_input("jdcm");
    BudgetTranslator translator(engine.get());
    Syllabifier syllabifier("' ", true, false);
    SyllableGraph graph;
    auto* dictionary = translator.dictionary();
    syllabifier.BuildSyllableGraph("wxzghj", *dictionary->prism(), &graph);
    auto original = dictionary->Lookup(graph, 0);
    auto control = dictionary->Lookup(graph, 0);
    Require(original && control, "Native iterator fixture did not match words");
    for (auto& group : *original) {
      auto snapshot = group.second.Clone();
      for (size_t index = 0; index < 64 && !snapshot.exhausted(); ++index)
        snapshot.Next();
      auto& expected = control->at(group.first);
      for (size_t index = 0; index < 16 && !expected.exhausted(); ++index) {
        Require(!group.second.exhausted() &&
                    group.second.Peek()->text == expected.Peek()->text &&
                    group.second.Peek()->code == expected.Peek()->code,
                "Scanning a snapshot mutated the original dictionary iterator");
        group.second.Next();
        expected.Next();
      }
    }
    auto query = [&]() {
      Segment segment(0, context->input().size());
      segment.tags.insert("abc");
      return translator.Query(context->input(), segment);
    };
    auto first = query();
    Require(first && !first->exhausted(), "Initial query lost complete words");
    Require(first->Peek()->text() == u8"\u6781\u5ea6\u8d85\u5356",
            "Sentence construction unconditionally replaced the complete word");
    bool found_sentence = false;
    for (size_t index = 0; index < 256 && !first->exhausted(); ++index) {
      auto candidate = first->Peek();
      if (candidate && candidate->type() == "sentence") {
        Require(candidate->start() == 0 && candidate->end() == 4,
                "Composed candidate lost its input span");
        found_sentence = true;
      }
      first->Next();
    }
    Require(found_sentence,
            "Complete dictionary words still block composition");
    auto budget = translator.budget();
    Require(budget && budget->positions_remaining < 48,
            "Abbreviation search did not consume its budget");
    const auto positions = budget->positions_remaining;
    auto second = query();
    Require(second && translator.budget() == budget &&
                budget->positions_remaining <= positions,
            "Repeated query replenished the composition budget");
    budget->positions_remaining = 0;
    budget->expansions_remaining = 0;
    budget->system_lookup = {0, 0};
    budget->user_lookup = {0, 0};
    auto exhausted = query();
    Require(exhausted && exhausted->Peek() && translator.budget() == budget &&
                !budget->expansions_remaining,
            "Budget exhaustion removed direct words or reset the allowance");
    Require(context->RefreshNonConfirmedComposition(),
            "Identical input did not refresh the actual composition");
    auto refreshed = query();
    Require(refreshed && translator.budget() != budget &&
                translator.budget()->expansions_remaining > 0,
            "Identical input refresh did not create a new budget");
    budget = translator.budget();
    context->set_caret_pos(2);
    query();
    Require(translator.budget() != budget,
            "Caret recomposition reused a stale budget");
    budget = translator.budget();
    context->Clear();
    context->set_input("jdcm");
    auto restarted = query();
    Require(restarted && translator.budget() != budget,
            "Clearing and retyping reused a stale budget");
    lime::set_user_dict_writes_enabled(false);
    auto menu = context->composition().back().menu;
    Require(menu != nullptr, "Actual engine did not provide a menu");
    string committed;
    auto connection =
        engine->sink().connect([&](const string& text) { committed += text; });
    bool selected = false;
    for (size_t index = 0; index < 256; ++index) {
      auto candidate = menu->GetCandidateAt(index);
      if (!candidate)
        break;
      if (candidate->type() != "sentence")
        continue;
      const auto expected_text = candidate->text();
      Require(context->Select(index), "Actual menu candidate selection failed");
      Require(committed == expected_text && context->input().empty(),
              "Selecting a composed candidate committed a different result");
      selected = true;
      break;
    }
    connection.disconnect();
    lime::set_user_dict_writes_enabled(true);
    Require(selected, "Actual menu dropped composed candidates");
    context->set_input("zgnzmk");
    Require(context->GetSelectedCandidate() &&
                context->GetSelectedCandidate()->text() ==
                    u8"\u8fd9\u4e2a\u4f60\u600e\u4e48\u770b",
            "Expanded search replaced the deployed sentence head");
    context->Clear();
    context->set_input("jidianchumen");
    Require(context->GetSelectedCandidate() &&
                context->GetSelectedCandidate()->text() ==
                    u8"\u51e0\u70b9\u51fa\u95e8",
            "Abbreviation search changed a complete spelling result");
    context->Clear();
    context->set_input("wxzghj");
    menu = context->composition().back().menu;
    bool found_target = false;
    for (size_t index = 0; index < 256; ++index) {
      auto candidate = menu->GetCandidateAt(index);
      if (!candidate)
        break;
      if (candidate->text() == u8"\u6211\u73b0\u5728\u521a\u56de\u5bb6") {
        Require(candidate->start() == 0 && candidate->end() == 6,
                "Long abbreviation target lost its input coverage");
        found_target = true;
        break;
      }
    }
    Require(found_target, "Long abbreviation target was pruned from the menu");
  }
  api->finalize();
  std::cout << "PASS native composition budget and exhaustion scenarios\n";
}
