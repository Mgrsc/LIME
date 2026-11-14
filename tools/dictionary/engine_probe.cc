#include <rime_api.h>
#include <rime/candidate.h>
#include <rime/context.h>
#include <rime/gear/lime_user_dict_writes.h>
#include <rime/key_event.h>
#include <rime/menu.h>
#include <rime/service.h>
#include <filesystem>
#include <iostream>
#include <sstream>
#include <string>

int main(int argc, char** argv) {
  if (argc != 3 && argc != 4) return 2;
  const bool persistent = argc == 4 && std::string(argv[3]) == "persistent";
  if (argc == 4 && !persistent) return 2;
  const std::string shared = std::filesystem::absolute(argv[1]).string();
  const std::string user = std::filesystem::absolute(argv[2]).string();
  const std::string prebuilt = shared + "/build";
  const std::string staging = user + "/build";
  std::filesystem::create_directories(staging);
  auto* api = rime_get_api();
  RIME_STRUCT(RimeTraits, traits);
  traits.shared_data_dir = shared.c_str();
  traits.user_data_dir = user.c_str();
  traits.prebuilt_data_dir = prebuilt.c_str();
  traits.staging_dir = staging.c_str();
  traits.app_name = "rime.lime_preedit_analysis";
  traits.min_log_level = 3;
  traits.log_dir = user.c_str();
  api->setup(&traits);
  api->initialize(&traits);
  std::string schema;
  std::string input;
  std::string operations;
  std::string active_schema;
  RimeSessionId session = 0;
  while (std::cin >> schema >> input >> operations) {
    if (!session) session = api->create_session();
    if (!session) return 3;
    if (active_schema != schema) {
      if (!api->select_schema(session, schema.c_str())) return 3;
      active_schema = schema;
    }
    api->set_option(session, "ascii_mode", False);
    api->set_option(session, "emoji", False);
    api->set_option(session, "traditionalization", False);
    for (const unsigned char key : input) api->process_key(session, key, 0);
    auto dump = [&](const std::string& phase) {
      RIME_STRUCT(RimeContext, context);
      if (!api->get_context(session, &context)) return false;
      std::cout << schema << '\t' << input << '\t' << phase << '\t'
                << api->get_input(session) << '\t' << api->get_caret_pos(session)
                << '\t' << (context.composition.preedit ? context.composition.preedit : "")
                << '\t' << context.composition.cursor_pos
                << '\t' << context.menu.page_no
                << '\t' << context.menu.highlighted_candidate_index;
      auto internal = rime::Service::instance().GetSession(session);
      if (internal->context()->HasMenu()) {
        auto menu = internal->context()->composition().back().menu;
        for (int index = 0; index < context.menu.num_candidates; ++index) {
          auto candidate = menu->GetCandidateAt(context.menu.page_no * context.menu.page_size + index);
          std::cout << '\t' << candidate->text() << '|' << candidate->comment()
                    << '|' << candidate->type() << '|' << candidate->start()
                    << '|' << candidate->end();
        }
      }
      const auto mapped = internal->context()->composition().GetPreedit(
          internal->context()->input(), internal->context()->caret_pos(), "").mapped;
      std::cout << "\tmap=";
      for (const auto& [display, raw] : mapped.Utf16Boundaries())
        std::cout << display << ':' << raw << ',';
      if (persistent) {
        RIME_STRUCT(RimeCommit, commit);
        std::cout << "\tcommit=";
        if (api->get_commit(session, &commit)) {
          std::cout << (commit.text ? commit.text : "");
          api->free_commit(&commit);
        }
      }
      std::cout << '\n';
      api->free_context(&context);
      return true;
    };
    if (!dump("typed")) return 4;
    std::istringstream stream(operations);
    std::string operation;
    int index = 0;
    while (std::getline(stream, operation, ',')) {
      if (operation == "none") continue;
      if (operation == "clear") {
        api->clear_composition(session);
      } else if (operation == "writes=off" || operation == "writes=on") {
        lime::set_user_dict_writes_enabled(operation == "writes=on");
      } else if (operation.rfind("pick=", 0) == 0) {
        auto internal = rime::Service::instance().GetSession(session);
        if (!internal->context()->HasMenu()) return 5;
        auto menu = internal->context()->composition().back().menu;
        bool found = false;
        const size_t candidate_limit = 200;
        for (size_t candidate_index = 0; candidate_index < candidate_limit; ++candidate_index) {
          auto candidate = menu->GetCandidateAt(candidate_index);
          if (!candidate) break;
          if (candidate->text() == operation.substr(5)) {
            found = api->select_candidate(session, candidate_index);
            break;
          }
        }
        if (!found) return 6;
      } else {
        rime::KeyEvent key(operation);
        if (!key.keycode()) return 7;
        api->process_key(session, key.keycode(), key.modifier());
      }
      if (!dump(std::to_string(++index) + ":" + operation)) return 8;
    }
    if (!persistent) {
      api->destroy_session(session);
      session = 0;
      active_schema.clear();
    }
  }
  if (session) api->destroy_session(session);
  api->finalize();
  return 0;
}
