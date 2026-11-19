#include "../app/src/main/cpp/jni/lime_jni.cc"
#include <cassert>
#include <chrono>
#include <iostream>
#include <thread>
#include <tuple>
#include <rime/algo/calculus.h>

void rime_require_module_predict() {}

struct Snapshot {
  std::string raw;
  std::string commit;
  std::string text;
  std::vector<std::pair<int, int>> boundaries;
  int cursor;
  int display_cursor;
  int selection_start;
  int selection_end;
  int page;
  int locked;
  jlong revision = 0;
};

class Harness {
 public:
  Harness(JNIEnv* env, jclass rime_class, jmethodID select_snapshot)
      : env_(env), rime_class_(rime_class), select_snapshot_(select_snapshot) {}

  Snapshot Read(jobject value) {
    if (env_->ExceptionCheck()) {
      env_->ExceptionDescribe();
      std::abort();
    }
    assert(value);
    auto composition = env_->GetObjectField(value, env_->GetFieldID(g_cache.snapshotClass,
        "composition", "Lorg/bitfennec/lime/core/RimeComposition;"));
    auto page = env_->GetObjectField(value, env_->GetFieldID(g_cache.snapshotClass,
        "page", "Lorg/bitfennec/lime/core/RimeCandidatePage;"));
    auto t9 = env_->GetObjectField(value, env_->GetFieldID(g_cache.snapshotClass,
        "t9Metadata", "Lorg/bitfennec/lime/core/RimeT9Metadata;"));
    Snapshot result;
    result.revision = env_->GetLongField(value, env_->GetFieldID(g_cache.snapshotClass,
        "candidateRevision", "J"));
    result.raw = Text(value, g_cache.snapshotClass, "rawInput");
    auto commit = static_cast<jstring>(env_->GetObjectField(value,
        env_->GetFieldID(g_cache.snapshotClass, "commitText", "Ljava/lang/String;")));
    if (commit) {
      assert(read_utf8_string(env_, commit, result.commit));
      env_->DeleteLocalRef(commit);
    }
    result.text = Text(composition, g_cache.compClass, "preedit");
    result.cursor = Integer(composition, g_cache.compClass, "cursorPos");
    result.display_cursor = Integer(composition, g_cache.compClass, "displayCursorPos");
    result.selection_start = Integer(composition, g_cache.compClass, "selStart");
    result.selection_end = Integer(composition, g_cache.compClass, "selEnd");
    result.page = Integer(page, g_cache.candidatePageClass, "pageNo");
    result.locked = t9 ? Integer(t9, g_cache.t9MetadataClass, "lockedCount") : 0;
    auto display = Integers(composition, "displayBoundaries");
    auto raw = Integers(composition, "rawBoundaries");
    assert(display.size() == raw.size());
    for (size_t index = 0; index < display.size(); ++index) {
      assert(raw[index] >= 0 && size_t(raw[index]) <= result.raw.size());
      if (index)
        assert(display[index] > display[index - 1] && raw[index] >= raw[index - 1]);
      result.boundaries.emplace_back(display[index], raw[index]);
    }
    std::u16string utf16;
    utf8::utf8to16(result.text.begin(), result.text.end(), std::back_inserter(utf16));
    for (const int offset : {result.display_cursor, result.selection_start, result.selection_end}) {
      assert(offset >= 0 && offset <= int(utf16.size()));
      assert(offset == 0 || offset == int(utf16.size()) || utf16[offset] < 0xdc00 || utf16[offset] > 0xdfff);
    }
    assert(result.selection_start <= result.selection_end);
    if (!result.text.empty()) {
      assert(!display.empty() && display.front() == 0 && display.back() == int(utf16.size()));
      for (const auto offset : display)
        assert(offset == 0 || offset == int(utf16.size()) || utf16[offset] < 0xdc00 || utf16[offset] > 0xdfff);
    }
    env_->DeleteLocalRef(composition);
    env_->DeleteLocalRef(page);
    if (t9) env_->DeleteLocalRef(t9);
    env_->DeleteLocalRef(value);
    return result;
  }

  Snapshot Type(const std::string& schema, const std::string& input) {
    Read(native_clearRimeCompositionAndSnapshot(env_, nullptr));
    auto selected = new_utf8_string(env_, schema);
    assert(native_selectRimeSchema(env_, nullptr, selected));
    env_->DeleteLocalRef(selected);
    for (const auto option : {"ascii_mode", "emoji", "traditionalization"})
      g_rime_api->set_option(g_session_id, option, False);
    for (unsigned char key : input)
      Key(key);
    return Read(build_snapshot_locked(env_, JNI_TRUE));
  }

  Snapshot Key(int code) {
    return Read(native_processRimeKeyAndSnapshot(env_, nullptr, code, 0));
  }

  Snapshot Move(int raw) {
    auto current = Read(build_snapshot_locked(env_, JNI_TRUE));
    for (size_t moves = 0; current.cursor != raw && moves <= current.raw.size(); ++moves)
      current = Key(RimeGetKeycodeByName(current.cursor > raw ? "KP_Left" : "KP_Right"));
    assert(current.cursor == raw);
    return current;
  }

  Snapshot Pick(const std::string& text, bool explicit_selection = true) {
    auto context = rime::Service::instance().GetSession(g_session_id)->context();
    assert(context->HasMenu());
    auto menu = context->composition().back().menu;
    auto current = Read(build_snapshot_locked(env_, JNI_TRUE));
    for (size_t index = 0; index < 200; ++index) {
      auto candidate = menu->GetCandidateAt(index);
      if (!candidate) break;
      if (candidate->text() == text)
        return Read(env_->CallStaticObjectMethod(rime_class_, select_snapshot_,
            current.page, jint(index / 15), jint(index % 15),
            explicit_selection ? JNI_TRUE : JNI_FALSE, current.revision));
    }
    std::abort();
  }

  Snapshot Lock(const std::string& syllable) {
    auto value = new_utf8_string(env_, syllable);
    auto result = Read(native_selectT9PrefixAndSnapshot(env_, nullptr, value));
    env_->DeleteLocalRef(value);
    return result;
  }

  Snapshot Page(int page, int direction) {
    return Read(native_moveRimeCandidatePageAndSnapshot(env_, nullptr, page, direction));
  }

 private:
  std::string Text(jobject object, jclass type, const char* name) {
    auto value = static_cast<jstring>(env_->GetObjectField(object,
        env_->GetFieldID(type, name, "Ljava/lang/String;")));
    std::string result;
    assert(value && read_utf8_string(env_, value, result));
    env_->DeleteLocalRef(value);
    return result;
  }

  int Integer(jobject object, jclass type, const char* name) {
    return env_->GetIntField(object, env_->GetFieldID(type, name, "I"));
  }

  std::vector<jint> Integers(jobject object, const char* name) {
    auto value = static_cast<jintArray>(env_->GetObjectField(object,
        env_->GetFieldID(g_cache.compClass, name, "[I")));
    assert(value);
    std::vector<jint> result(env_->GetArrayLength(value));
    env_->GetIntArrayRegion(value, 0, result.size(), result.data());
    env_->DeleteLocalRef(value);
    return result;
  }

  JNIEnv* env_;
  jclass rime_class_;
  jmethodID select_snapshot_;
};

static void TestLearningSelection(Harness& harness, JNIEnv* env) {
  native_setUserDictWritesEnabled(env, nullptr, JNI_TRUE);
  const std::string wrong = "\u7537\u7684\u96be\u9053";
  const std::string correct = "\u7537\u7684\u5973\u7684";
  auto first = [&]() {
    auto* context = rime::Service::instance().GetSession(g_session_id)->context();
    assert(context->get_property(lime::kExplicitLearningSelection).empty());
    auto phrase = rime::As<rime::Phrase>(
        rime::Candidate::GetGenuineCandidate(context->GetSelectedCandidate()));
    assert(phrase);
    return std::make_pair(phrase->text(), phrase->entry().commit_count);
  };
  auto preference_count = [&](const std::string& text) {
    auto* context = rime::Service::instance().GetSession(g_session_id)->context();
    if (!context->HasMenu())
      return 0;
    auto menu = context->composition().back().menu;
    for (size_t index = 0; index < 200; ++index) {
      auto candidate = menu->GetCandidateAt(index);
      if (!candidate) break;
      if (candidate->type() != "user_phrase") continue;
      auto phrase = rime::As<rime::Phrase>(
          rime::Candidate::GetGenuineCandidate(candidate));
      if (phrase && phrase->text() == text)
        return phrase->entry().commit_count;
    }
    return 0;
  };
  harness.Type("pinyin", "zdjd");
  harness.Pick("\u771f\u7684\u5047\u7684", false);
  harness.Type("pinyin", "zhendejiade");
  assert(preference_count("\u771f\u7684\u5047\u7684") == 0);
  harness.Type("pinyin", "zdjd");
  assert(first().first != "\u771f\u7684\u5047\u7684");
  const auto initial = harness.Type("pinyin", "nandenandao");
  assert(!native_selectRimeCandidateOnPageAndSnapshot(env, nullptr,
      initial.page + 1, initial.page, 0, JNI_TRUE, initial.revision));
  assert(!native_selectRimeCandidateOnPageAndSnapshot(env, nullptr,
      initial.page, initial.page, 1000, JNI_TRUE, initial.revision));
  harness.Pick(wrong, false);
  harness.Type("pinyin", "ndnd");
  assert(first() == std::make_pair(correct, 0));
  harness.Type("pinyin", "nandenandao");
  harness.Pick(wrong);
  harness.Type("pinyin", "ndnd");
  assert(first() == std::make_pair(correct, 0));
  harness.Type("pinyin", "nandenandao");
  assert(first() == std::make_pair(wrong, 1));
  harness.Type("pinyin", "ndnd");
  harness.Pick(correct);
  harness.Type("pinyin", "ndnd");
  assert(first().first == correct);
  assert(preference_count(correct) == 1);
  for (int repeat = 0; repeat < 5; ++repeat) {
    harness.Type("pinyin", "nandenandao");
    harness.Pick(wrong, false);
    harness.Type("pinyin", "ndnd");
    assert(first() == std::make_pair(correct, 1));
  }
  harness.Type("pinyin", "nandenandao");
  harness.Key('1');
  harness.Type("pinyin", "nandenandao");
  assert(first() == std::make_pair(wrong, 2));
  harness.Type("pinyin", "ndnd");
  assert(first() == std::make_pair(correct, 1));
  for (const auto& [schema, input, phrase] :
       std::vector<std::tuple<std::string, std::string, std::string>>{
           {"double_pinyin_flypy", "vsgo", "\u4e2d\u56fd"},
           {"t9_pinyin", "64426", "\u4f60\u597d"}}) {
    harness.Type(schema, input);
    harness.Pick(phrase, false);
    harness.Type(schema, input);
    assert(first() == std::make_pair(phrase, 0));
    harness.Pick(phrase);
    harness.Type(schema, input);
    assert(first() == std::make_pair(phrase, 1));
  }
  native_setUserDictWritesEnabled(env, nullptr, JNI_FALSE);
  harness.Type("pinyin", "ndnd");
  harness.Pick(correct);
  harness.Type("pinyin", "ndnd");
  assert(first().first == correct);
  assert(preference_count(correct) == 1);
  assert(preference_count(wrong) == 2);
}

static void TestCandidateIdentity(Harness& harness, JNIEnv* env) {
  native_setUserDictWritesEnabled(env, nullptr, JNI_TRUE);
  const auto displayed = harness.Type("pinyin", "ni");
  harness.Key('h');
  harness.Key('a');
  const auto current = harness.Key('o');
  assert(current.page == displayed.page && current.revision != displayed.revision);
  assert(!native_selectRimeCandidateOnPageAndSnapshot(env, nullptr,
      current.page, displayed.page, 0, JNI_TRUE, displayed.revision));
  assert(harness.Read(build_snapshot_locked(env, JNI_TRUE)).raw == "nihao");

  harness.Type("pinyin", "xin");
  harness.Pick("\u99a8");
  const auto old_deletion = harness.Type("pinyin", "xin");
  harness.Type("pinyin", "rou");
  harness.Pick("\u63c9");
  const auto current_deletion = harness.Type("pinyin", "rou");
  assert(!native_deleteRimeCandidateOnPageAndSnapshot(env, nullptr,
      current_deletion.page, old_deletion.page, 0, old_deletion.revision));
  assert(native_getCandidateDeletableType(env, nullptr, 0, 0, old_deletion.revision) == 0);
  assert(native_getCandidateDeletableType(env, nullptr, 0, 0, current_deletion.revision) == 2);

  const auto first_page = harness.Type("pinyin", "ni");
  auto* context = rime::Service::instance().GetSession(g_session_id)->context();
  const auto expected = context->GetSelectedCandidate()->text();
  const auto next_page = harness.Read(native_moveRimeCandidatePageAndSnapshot(
      env, nullptr, first_page.page, 1));
  assert(next_page.page > first_page.page && next_page.revision == first_page.revision);
  const auto selected = harness.Read(native_selectRimeCandidateOnPageAndSnapshot(env, nullptr,
      next_page.page, first_page.page, 0, JNI_TRUE, first_page.revision));
  assert(selected.raw.empty() && selected.revision != first_page.revision);
  assert(selected.commit == expected);

  const auto deletion_page = harness.Type("pinyin", "rou");
  const auto later_page = harness.Read(native_moveRimeCandidatePageAndSnapshot(
      env, nullptr, deletion_page.page, 1));
  assert(later_page.revision == deletion_page.revision);
  const auto deleted = harness.Read(native_deleteRimeCandidateOnPageAndSnapshot(env, nullptr,
      later_page.page, deletion_page.page, 0, deletion_page.revision));
  assert(deleted.revision != deletion_page.revision);
  assert(native_getCandidateDeletableType(env, nullptr, 0, 0, deleted.revision) == 0);

  const auto before_clear = harness.Type("pinyin", "ni");
  const auto after_clear = harness.Type("pinyin", "ni");
  assert(!native_selectRimeCandidateOnPageAndSnapshot(env, nullptr,
      after_clear.page, before_clear.page, 0, JNI_TRUE, before_clear.revision));
  const auto before_segment = harness.Type("pinyin", "nihaoshijie");
  const auto after_segment = harness.Pick("\u4f60\u597d");
  assert(!native_selectRimeCandidateOnPageAndSnapshot(env, nullptr,
      after_segment.page, before_segment.page, 0, JNI_TRUE, before_segment.revision));
}

static void TestProjection() {
  const auto invalid = rime::MappedPreedit::Input("\xf0\x80");
  assert(!invalid.valid() && invalid.Utf16Boundaries().empty());
  assert(invalid.Utf16Offset(2) == 0 && invalid.ByteOffsetForRaw(1) == 0);
  rime::Composition malformed;
  malformed.Reset("a");
  rime::Segment segment(0, 1);
  segment.menu = rime::New<rime::Menu>();
  segment.menu->AddTranslation(rime::New<rime::UniqueTranslation>(
      rime::New<rime::SimpleCandidate>("test", 0, 1, "x", "", "\xf0\x80")));
  segment.menu->Prepare(1);
  malformed.push_back(segment);
  assert(!malformed.GetPreedit("a", 1, "").mapped.valid());
  const auto astral = rime::MappedPreedit::Literal("\U0001f600a", {0, 2});
  assert(astral.Utf16Offset(0) == 0 && astral.Utf16Offset(3) == 0);
  assert(astral.Utf16Offset(4) == 2 && astral.Utf16Offset(5) == 3);
  assert((astral.Utf16Boundaries() == std::vector<std::pair<int, int>>{{0, 0}, {3, 2}}));
  rime::Calculus calculus;
  auto mapped = rime::MappedPreedit::Input("nt");
  for (const auto rule : {"xform/(\\w)t/$1ve/", "xform/([nljqxy])v/$1\u00fc/"}) {
    std::unique_ptr<rime::Calculation> calculation(calculus.Parse(rule));
    assert(calculation && calculation->ApplyMapped(&mapped));
  }
  assert(mapped.text == "n\u00fce");
  assert((mapped.Utf16Boundaries() == std::vector<std::pair<int, int>>{{0, 0}, {1, 1}, {3, 2}}));
  for (const auto& test : std::vector<std::pair<std::string, std::string>>{
      {"xform/^([aoe])\\1(\\w)/$1$2/", "aab"},
      {"xform/(a)(b)/$2$1/", "ab"},
      {"xform/a/X/", "aba"},
      {"xlit/ab/\u00fc\U0001f600/", "ab"},
      {"xform/b//", "abc"}}) {
    std::unique_ptr<rime::Calculation> calculation(calculus.Parse(test.first));
    assert(calculation);
    rime::Spelling plain(test.second);
    auto value = rime::MappedPreedit::Input(test.second);
    calculation->Apply(&plain);
    calculation->ApplyMapped(&value);
    assert(value.valid() && value.text == plain.str);
    auto boundaries = value.Utf16Boundaries();
    assert(!boundaries.empty() && boundaries.front().second == 0 && boundaries.back().second == int(test.second.size()));
  }
}

static void TestT9PrefixUndo(Harness& harness, JNIEnv* env) {
  const int backspace = RimeGetKeycodeByName("BackSpace");
  const auto original = harness.Type("t9_pinyin", "64426744");
  const auto first = harness.Lock("ni");
  const auto second = harness.Lock("hao");
  const auto third = harness.Lock("shi");
  assert(first.locked == 1 && second.locked == 2 && third.locked == 3);
  for (const auto& expected : {second, first, original}) {
    const auto actual = harness.Key(backspace);
    assert(actual.raw == expected.raw && actual.cursor == expected.cursor);
    assert(actual.locked == expected.locked && actual.text == expected.text);
  }
  assert(harness.Key(backspace).raw == "6442674");

  harness.Type("t9_pinyin", "64'426");
  assert(harness.Lock("hao").locked == 1);
  auto restored = harness.Key(backspace);
  assert(restored.raw == "64'426" && restored.locked == 0);
  assert(harness.Key(backspace).raw == "64'42");

  harness.Type("t9_pinyin", "64426");
  harness.Lock("ni");
  auto edited = harness.Key('7');
  assert(edited.raw == "64'4267" && edited.locked == 1);
  edited = harness.Key(backspace);
  assert(edited.raw == "64'426" && edited.locked == 1);
  edited = harness.Key(backspace);
  assert(edited.raw == "64'42" && edited.locked == 1);

  harness.Type("t9_pinyin", "64426");
  harness.Lock("ni");
  harness.Lock("hao");
  harness.Lock("hao");
  assert(harness.Key(backspace).locked == 1);
  restored = harness.Key(backspace);
  assert(restored.raw == "64426" && restored.locked == 0);

  harness.Type("t9_pinyin", "64");
  harness.Lock("ni");
  assert(harness.Lock("mi").text == "mi");
  restored = harness.Key(backspace);
  assert(restored.raw == "64" && restored.locked == 1 && restored.text == "ni");
  assert(harness.Key(backspace).locked == 0);

  harness.Type("t9_pinyin", "64426");
  harness.Move(2);
  harness.Lock("ni");
  restored = harness.Key(backspace);
  assert(restored.raw == "64426" && restored.cursor == 2 && restored.locked == 0);
  assert(harness.Key(backspace).raw == "6426");

  harness.Type("t9_pinyin", "64426");
  harness.Lock("ni");
  harness.Lock("hao");
  assert(harness.Key('7').raw == "64'4267");
  assert(harness.Key(backspace).raw == "64'426");
  assert(harness.Key(backspace).raw == "64'42");

  harness.Type("t9_pinyin", "64426");
  harness.Lock("ni");
  harness.Lock("hao");
  harness.Move(5);
  assert(harness.Key(backspace).raw == "64'46");

  harness.Type("t9_pinyin", "64426");
  harness.Lock("ni");
  harness.Lock("hao");
  const auto committed = harness.Pick("\u4f60\u597d");
  assert(committed.raw.empty() && committed.locked == 0);
  assert(harness.Key(backspace).raw.empty());

  harness.Type("t9_pinyin", "64426");
  harness.Lock("ni");
  const auto selected = harness.Pick("\u4f60");
  assert(selected.text.find("\u4f60") == 0);
  const auto selected_erased = harness.Key(backspace);
  assert(selected_erased.raw == selected.raw && selected_erased.locked == selected.locked);
  assert(selected_erased.text.find("\u4f60") == std::string::npos);
  assert(harness.Key(backspace).raw == "64'42");

  harness.Type("t9_pinyin", "64426");
  harness.Lock("ni");
  harness.Lock("hao");
  const auto cleared = harness.Read(native_clearRimeCompositionAndSnapshot(env, nullptr));
  assert(cleared.raw.empty() && cleared.locked == 0);
  assert(harness.Key(backspace).raw.empty());

  harness.Type("t9_pinyin", "64426");
  harness.Lock("ni");
  auto schema = new_utf8_string(env, "pinyin");
  assert(native_selectRimeSchema(env, nullptr, schema));
  env->DeleteLocalRef(schema);
  restored = harness.Read(native_snapshotRimeState(env, nullptr));
  assert(restored.raw.empty() && restored.locked == 0);
  assert(harness.Key(backspace).raw.empty());

  harness.Type("t9_pinyin", "64426");
  harness.Lock("ni");
  assert(g_rime_api->destroy_session(g_session_id));
  restored = harness.Read(native_snapshotRimeState(env, nullptr));
  assert(restored.raw.empty() && restored.locked == 0);
  assert(harness.Key(backspace).raw.empty());
}

static void TestCandidateStateRefresh(Harness& harness, JNIEnv* env) {
  auto type = env->FindClass("org/bitfennec/lime/inputmethod/RimeEngine");
  assert(type);
  auto engine = env->GetStaticObjectField(type, env->GetStaticFieldID(type,
      "INSTANCE", "Lorg/bitfennec/lime/inputmethod/RimeEngine;"));
  auto refresh = env->GetMethodID(type, "refreshState", "()Ljava/lang/String;");
  auto revision = env->GetMethodID(type, "getCandidateRevision", "()J");
  auto select = env->GetMethodID(type, "selectCandidate", "(IZJ)Ljava/lang/String;");
  auto remove = env->GetMethodID(type, "deleteCandidate", "(IJ)Ljava/lang/String;");
  auto option = env->GetMethodID(type, "setImeOption", "(Ljava/lang/String;Z)V");
  auto expand = env->GetMethodID(type, "getNextExpandedCandidates", "()Ljava/util/List;");
  assert(engine && refresh && revision && select && remove && option && expand);
  auto refresh_state = [&]() {
    auto result = env->CallObjectMethod(engine, refresh);
    if (env->ExceptionCheck()) env->ExceptionDescribe();
    assert(!env->ExceptionCheck() && result);
    env->DeleteLocalRef(result);
    assert(env->CallLongMethod(engine, revision) == g_candidate_revision);
  };
  auto select_first = [&](jlong expected_revision) {
    auto result = static_cast<jstring>(env->CallObjectMethod(
        engine, select, 0, JNI_FALSE, expected_revision));
    if (env->ExceptionCheck()) env->ExceptionDescribe();
    assert(!env->ExceptionCheck() && result);
    std::string text;
    assert(read_utf8_string(env, result, text));
    env->DeleteLocalRef(result);
    return text;
  };
  for (const bool enabled : {false, true}) {
    harness.Type("pinyin", "nihao");
    refresh_state();
    const auto previous = env->CallLongMethod(engine, revision);
    auto name = new_utf8_string(env, "emoji");
    env->CallVoidMethod(engine, option, name, enabled ? JNI_TRUE : JNI_FALSE);
    env->DeleteLocalRef(name);
    assert(g_candidate_revision != previous);
    refresh_state();
    assert(!env->CallObjectMethod(engine, select, 0, JNI_FALSE, previous));
    assert(select_first(g_candidate_revision) == "\u4f60\u597d");
  }
  for (const int page_size : {15, 12}) {
    harness.Type("pinyin", "nihao");
    refresh_state();
    native_setRimePageSize(env, nullptr, page_size);
    refresh_state();
    assert(select_first(g_candidate_revision) == "\u4f60\u597d");
  }
  native_setRimePageSize(env, nullptr, 15);
  harness.Type("pinyin", "nihao");
  refresh_state();
  auto deletion = env->CallObjectMethod(engine, remove, 0, g_candidate_revision);
  assert(deletion && !env->ExceptionCheck());
  env->DeleteLocalRef(deletion);
  assert(env->CallLongMethod(engine, revision) == g_candidate_revision);
  harness.Type("pinyin", "nihao");
  refresh_state();
  const auto before_expansion = g_candidate_revision;
  auto expanded = env->CallObjectMethod(engine, expand);
  assert(expanded && !env->ExceptionCheck());
  env->DeleteLocalRef(expanded);
  assert(g_candidate_revision == before_expansion);
  auto name = new_utf8_string(env, "emoji");
  env->CallVoidMethod(engine, option, name, JNI_FALSE);
  env->DeleteLocalRef(name);
  refresh_state();
  const auto current = harness.Read(native_snapshotRimeState(env, nullptr));
  auto* context = rime::Service::instance().GetSession(g_session_id)->context();
  const auto expected = context->composition().back().menu->GetCandidateAt(
      current.page * 15)->text();
  assert(select_first(g_candidate_revision) == expected);

  harness.Type("pinyin", "nihaoshijie");
  harness.Move(5);
  refresh_state();
  assert(select_first(g_candidate_revision).empty());
  assert(g_rime_api->get_input(g_session_id) &&
      std::string(g_rime_api->get_input(g_session_id)).size() > 0);
  auto* api = g_rime_api;
  g_rime_api = nullptr;
  assert(!env->CallObjectMethod(engine, refresh));
  assert(!env->ExceptionCheck());
  g_rime_api = api;
  env->DeleteLocalRef(engine);
  env->DeleteLocalRef(type);
}

static void TestActorCandidateState(Harness& harness, JNIEnv* env) {
  auto pipeline_type = env->FindClass("org/bitfennec/lime/inputmethod/EnginePipeline");
  assert(pipeline_type);
  auto pipeline = env->GetStaticObjectField(pipeline_type,
      env->GetStaticFieldID(pipeline_type, "INSTANCE",
          "Lorg/bitfennec/lime/inputmethod/EnginePipeline;"));
  auto invalidate = env->GetMethodID(pipeline_type, "invalidateSession", "()J");
  auto send = env->GetMethodID(pipeline_type, "send",
      "(Lorg/bitfennec/lime/inputmethod/EngineAction;)Z");
  auto state_flow = env->CallObjectMethod(pipeline, env->GetMethodID(pipeline_type,
      "getStateFlow", "()Lkotlinx/coroutines/flow/StateFlow;"));
  auto flow_type = env->FindClass("kotlinx/coroutines/flow/StateFlow");
  auto get_value = env->GetMethodID(flow_type, "getValue", "()Ljava/lang/Object;");
  auto state_type = env->FindClass("org/bitfennec/lime/inputmethod/EngineState");
  auto generation = env->GetMethodID(state_type, "getGen", "()J");
  auto revision = env->GetMethodID(state_type, "getCandidateRevision", "()J");
  auto ready = env->GetMethodID(state_type, "getEngineReady", "()Z");
  env->CallVoidMethod(pipeline, env->GetMethodID(pipeline_type,
      "setChinesePredictionEnabled", "(Z)V"), JNI_FALSE);
  env->CallLongMethod(pipeline, invalidate);
  jlong next_generation = 1000;
  auto dispatch = [&](jobject action, jlong gen, bool expected_ready = true) {
    assert(env->CallBooleanMethod(pipeline, send, action));
    env->DeleteLocalRef(action);
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(5);
    while (std::chrono::steady_clock::now() < deadline) {
      auto state = env->CallObjectMethod(state_flow, get_value);
      assert(state && !env->ExceptionCheck());
      const auto current = env->CallLongMethod(state, generation);
      if (current == gen) {
        assert(bool(env->CallBooleanMethod(state, ready)) == expected_ready);
        if (expected_ready)
          assert(env->CallLongMethod(state, revision) == g_candidate_revision);
        env->DeleteLocalRef(state);
        return;
      }
      env->DeleteLocalRef(state);
      std::this_thread::sleep_for(std::chrono::milliseconds(2));
    }
    assert(false && "Actor did not publish the requested generation");
  };
  auto option_type = env->FindClass("org/bitfennec/lime/inputmethod/EngineAction$SetImeOption");
  auto option_init = env->GetMethodID(option_type, "<init>", "(Ljava/lang/String;ZJ)V");
  auto apply_option = [&]() {
    auto name = new_utf8_string(env, "emoji");
    const auto gen = ++next_generation;
    auto action = env->NewObject(option_type, option_init, name, JNI_FALSE, gen);
    env->DeleteLocalRef(name);
    dispatch(action, gen);
  };
  for (int repeat = 0; repeat < 2; ++repeat) {
    harness.Type("pinyin", "nihao");
    apply_option();
    assert(std::string(g_rime_api->get_input(g_session_id)) == "nihao");
  }
  auto select_type = env->FindClass("org/bitfennec/lime/inputmethod/EngineAction$SelectCandidate");
  auto select_init = env->GetMethodID(select_type, "<init>",
      "(Lorg/bitfennec/lime/core/CandidateIdentity;ZJ)V");
  auto state = env->CallObjectMethod(state_flow, get_value);
  auto candidates = env->CallObjectMethod(state, env->GetMethodID(state_type,
      "getCandidates", "()Ljava/util/List;"));
  auto list_type = env->FindClass("java/util/List");
  auto candidate = env->CallObjectMethod(candidates,
      env->GetMethodID(list_type, "get", "(I)Ljava/lang/Object;"), 0);
  auto candidate_type = env->GetObjectClass(candidate);
  auto identity = env->CallObjectMethod(candidate, env->GetMethodID(candidate_type,
      "getIdentity", "()Lorg/bitfennec/lime/core/CandidateIdentity;"));
  const auto select_gen = ++next_generation;
  dispatch(env->NewObject(select_type, select_init, identity, JNI_FALSE, select_gen), select_gen);
  assert(std::string(g_rime_api->get_input(g_session_id)).empty());
  auto page_no = env->GetMethodID(state_type, "getCandidatePageNo", "()I");
  auto has_next_page = env->GetMethodID(state_type, "getHasNextCandidatePage", "()Z");
  auto state_candidates = env->GetMethodID(state_type, "getCandidates", "()Ljava/util/List;");
  auto list_size = env->GetMethodID(list_type, "size", "()I");
  auto delete_type = env->FindClass("org/bitfennec/lime/inputmethod/EngineAction$DeleteKey");
  auto delete_init = env->GetMethodID(delete_type, "<init>", "(J)V");
  auto expand_type = env->FindClass("org/bitfennec/lime/inputmethod/EngineAction$LoadMoreExpandedCandidates");
  auto expand_init = env->GetMethodID(expand_type, "<init>", "(IJJ)V");
  auto case_type = env->FindClass("org/bitfennec/lime/inputmethod/EngineAction$SetCharCase");
  auto case_init = env->GetMethodID(case_type, "<init>", "(IJ)V");
  auto enqueue_lock = env->GetStaticObjectField(pipeline_type,
      env->GetStaticFieldID(pipeline_type, "enqueueLock", "Ljava/lang/Object;"));
  assert(page_no && has_next_page && state_candidates && list_size &&
         delete_init && expand_init && case_init && enqueue_lock &&
         !env->ExceptionCheck());
  harness.Type("pinyin", "ni");
  apply_option();
  auto initial_state = env->CallObjectMethod(state_flow, get_value);
  const auto initial_page = env->CallIntMethod(initial_state, page_no);
  const auto initial_revision = env->CallLongMethod(initial_state, revision);
  assert(env->CallBooleanMethod(initial_state, has_next_page));
  assert(env->MonitorEnter(enqueue_lock) == JNI_OK);
  auto delete_action = env->NewObject(delete_type, delete_init, ++next_generation);
  auto stale_expand = env->NewObject(expand_type, expand_init,
      initial_page, initial_revision, ++next_generation);
  assert(env->CallBooleanMethod(pipeline, send, delete_action));
  assert(env->CallBooleanMethod(pipeline, send, stale_expand));
  assert(env->MonitorExit(enqueue_lock) == JNI_OK);
  const auto barrier_gen = ++next_generation;
  dispatch(env->NewObject(case_type, case_init, 0, barrier_gen), barrier_gen);
  auto rejected_state = env->CallObjectMethod(state_flow, get_value);
  const auto current_revision = env->CallLongMethod(rejected_state, revision);
  assert(current_revision != initial_revision);
  assert(env->CallIntMethod(rejected_state, page_no) == initial_page);
  assert(env->CallBooleanMethod(rejected_state, has_next_page));
  assert(std::string(g_rime_api->get_input(g_session_id)) == "n");
  auto first_page = env->CallObjectMethod(rejected_state, state_candidates);
  const auto first_page_size = env->CallIntMethod(first_page, list_size);
  const auto expand_gen = ++next_generation;
  dispatch(env->NewObject(expand_type, expand_init,
      initial_page, current_revision, expand_gen), expand_gen);
  auto expanded_state = env->CallObjectMethod(state_flow, get_value);
  auto expanded_candidates = env->CallObjectMethod(expanded_state, state_candidates);
  assert(env->CallIntMethod(expanded_state, page_no) == initial_page + 1);
  assert(env->CallLongMethod(expanded_state, revision) == current_revision);
  assert(env->CallIntMethod(expanded_candidates, list_size) > first_page_size);
  for (jobject reference : std::vector<jobject>{
           expanded_candidates, expanded_state, first_page, rejected_state,
           stale_expand, delete_action, initial_state, enqueue_lock, case_type,
           expand_type, delete_type})
    env->DeleteLocalRef(reference);
  std::cout << "PASS: Actor rejects stale expanded paging after a queued delete and accepts a fresh retry\n";
  for (const auto& name : {"SpaceKey", "CommitThenKey", "CommitThenText"}) {
    harness.Type("pinyin", "nihao");
    apply_option();
    auto option_name = new_utf8_string(env, "emoji");
    native_setRimeOption(env, nullptr, option_name, JNI_FALSE);
    env->DeleteLocalRef(option_name);
    const auto gen = ++next_generation;
    const std::string class_name =
        "org/bitfennec/lime/inputmethod/EngineAction$" + std::string(name);
    auto action_type = env->FindClass(class_name.c_str());
    jobject action;
    if (std::string(name) == "SpaceKey") {
      action = env->NewObject(action_type,
          env->GetMethodID(action_type, "<init>", "(JZ)V"), gen, JNI_TRUE);
    } else if (std::string(name) == "CommitThenKey") {
      action = env->NewObject(action_type,
          env->GetMethodID(action_type, "<init>", "(IJ)V"), 61, gen);
    } else {
      auto text = new_utf8_string(env, ",");
      action = env->NewObject(action_type,
          env->GetMethodID(action_type, "<init>", "(Ljava/lang/String;ZJ)V"),
          text, JNI_TRUE, gen);
      env->DeleteLocalRef(text);
    }
    dispatch(action, gen);
    assert(std::string(g_rime_api->get_input(g_session_id)) == "nihao");
    env->DeleteLocalRef(action_type);
  }
  auto* api = g_rime_api;
  g_rime_api = nullptr;
  auto option_name = new_utf8_string(env, "emoji");
  const auto unavailable_gen = ++next_generation;
  auto unavailable = env->NewObject(option_type, option_init,
      option_name, JNI_FALSE, unavailable_gen);
  env->DeleteLocalRef(option_name);
  dispatch(unavailable, unavailable_gen, false);
  auto failed_state = env->CallObjectMethod(state_flow, get_value);
  auto failed_candidates = env->CallObjectMethod(failed_state,
      env->GetMethodID(state_type, "getCandidates", "()Ljava/util/List;"));
  assert(env->CallIntMethod(failed_candidates,
      env->GetMethodID(list_type, "size", "()I")) == 0);
  env->DeleteLocalRef(failed_candidates);
  env->DeleteLocalRef(failed_state);
  g_rime_api = api;
  apply_option();
  assert(std::string(g_rime_api->get_input(g_session_id)) == "nihao");
  for (jobject reference : std::vector<jobject>{
           identity, candidate_type, candidate, candidates, state, select_type,
           list_type, option_type, state_type, flow_type, state_flow, pipeline,
           pipeline_type})
    env->DeleteLocalRef(reference);
  std::cout << "PASS: Actor option synchronization, candidate selection and rejected finalization recovery\n";
}

int main(int argc, char** argv) {
  assert(argc == 4 || argc == 5);
  TestProjection();
  JavaVM* vm = nullptr;
  JNIEnv* env = nullptr;
  std::string classpath = std::string("-Djava.class.path=") + argv[3];
  JavaVMOption option{classpath.data(), nullptr};
  JavaVMInitArgs options{};
  options.version = JNI_VERSION_1_8;
  options.nOptions = 1;
  options.options = &option;
  assert(JNI_CreateJavaVM(&vm, reinterpret_cast<void**>(&env), &options) == JNI_OK);
  init_cache(env);
  assert(g_cache.compInit && g_cache.snapshotInit);
  auto rime_class = env->FindClass("org/bitfennec/lime/core/Rime");
  assert(rime_class);
  const auto selection_method = std::find_if(std::begin(g_methods), std::end(g_methods),
      [](const auto& method) {
        return std::string(method.name) == "selectRimeCandidateOnPageAndSnapshot";
      });
  assert(selection_method != std::end(g_methods));
  for (const auto& method : g_methods) {
    const std::string name(method.name);
    if (name == "selectRimeCandidateOnPageAndSnapshot" ||
        name == "snapshotRimeState" || name == "setRimeOption" ||
        name == "deleteRimeCandidateOnPageAndSnapshot" ||
        name == "clearRimeCompositionAndSnapshot" ||
        name == "processRimeKeyAndSnapshot" || name == "getRimeKeycodeByName" ||
        name == "moveRimeCandidatePageAndSnapshot")
      assert(env->RegisterNatives(rime_class, &method, 1) == JNI_OK);
  }
  auto select_snapshot = env->GetStaticMethodID(rime_class, "selectCandidateOnPageSnapshot",
      "(IIIZJ)Lorg/bitfennec/lime/core/RimeSnapshot;");
  assert(select_snapshot);
  std::filesystem::create_directories(std::string(argv[2]) + "/build");
  std::string prebuilt = std::string(argv[1]) + "/build";
  std::string staging = std::string(argv[2]) + "/build";
  RIME_STRUCT(RimeTraits, traits);
  traits.shared_data_dir = argv[1];
  traits.user_data_dir = argv[2];
  traits.prebuilt_data_dir = prebuilt.c_str();
  traits.staging_dir = staging.c_str();
  traits.app_name = "rime.lime_preedit_regression";
  traits.min_log_level = 3;
  traits.log_dir = argv[2];
  g_rime_api = rime_get_api();
  g_rime_api->setup(&traits);
  g_rime_api->initialize(&traits);
  g_rime_initialized = true;
  assert(get_session_locked());
  native_setRimePageSize(env, nullptr, 15);
  native_setUserDictWritesEnabled(env, nullptr, JNI_FALSE);
  Harness harness(env, rime_class, select_snapshot);
  for (const auto& test : std::vector<std::pair<std::string, std::string>>{
      {"wengzi", "weng zi"}, {"shengme", "sheng me"}, {"lvyou", "l\u00fc you"},
      {"duen", "duen"}, {"xiagn", "xiagn"}, {"weng'", "weng'"}, {"weng'zi", "weng'zi"}}) {
    const auto snapshot = harness.Type("pinyin", test.first);
    assert(snapshot.text == test.second && snapshot.raw == test.first);
  }
  for (const auto& test : std::vector<std::pair<std::string, std::string>>{
      {"pinyin", "lvyou"}, {"double_pinyin_flypy", "vsgo"}}) {
    auto snapshot = harness.Type(test.first, test.second);
    const int raw = test.first == "pinyin" ? 4 : 3;
    const int display = test.first == "pinyin" ? 5 : 7;
    assert(std::find(snapshot.boundaries.begin(), snapshot.boundaries.end(), std::make_pair(display, raw)) != snapshot.boundaries.end());
    const auto moved = harness.Move(raw);
    assert(moved.cursor == raw && moved.display_cursor == display);
    assert(harness.Key('a').raw == test.second.substr(0, raw) + "a" + test.second.substr(raw));
    assert(harness.Key(RimeGetKeycodeByName("BackSpace")).raw == test.second);
  }
  harness.Type("pinyin", "nihaoshijie");
  auto selected = harness.Pick("\u4f60\u597d");
  assert(selected.text == "\u4f60\u597dshi jie");
  assert(selected.selection_start == 2 && selected.selection_end == 9 && selected.display_cursor == 9);
  assert(std::find(selected.boundaries.begin(), selected.boundaries.end(), std::make_pair(8, 10)) != selected.boundaries.end());
  assert(harness.Move(10).cursor == 10);
  assert(harness.Key('a').raw == "nihaoshijiae");
  assert(harness.Key(RimeGetKeycodeByName("BackSpace")).raw == "nihaoshijie");
  harness.Move(8);
  const auto middle_selected = harness.Pick("\u662f");
  assert(middle_selected.raw == "nihaoshijie");
  assert(middle_selected.text == "\u4f60\u597d\u662fjie");
  harness.Type("t9_pinyin", "64426");
  auto locked = harness.Lock("ni");
  assert(locked.locked == 1);
  locked = harness.Lock("hao");
  assert(locked.locked == 2 && locked.text == "ni'hao");
  auto paged = harness.Page(locked.page, 1);
  assert(paged.raw == locked.raw && paged.text == locked.text && paged.boundaries == locked.boundaries);
  auto undone = harness.Key(RimeGetKeycodeByName("BackSpace"));
  assert(undone.raw == locked.raw && undone.locked == 1);
  undone = harness.Key(RimeGetKeycodeByName("BackSpace"));
  assert(undone.raw == "64426" && undone.locked == 0 && undone.cursor == 5);
  assert(harness.Key(RimeGetKeycodeByName("BackSpace")).raw == "6442");
  auto tail = harness.Type("t9_pinyin", "64426");
  auto erased = harness.Key(RimeGetKeycodeByName("BackSpace"));
  assert(erased.raw == "6442" && erased.text == tail.text.substr(0, tail.text.size() - 1));
  harness.Type("t9_pinyin", "6");
  auto expanded = harness.Lock("ni");
  assert(expanded.text == "ni" && expanded.boundaries.size() == 2);
  assert(harness.Key(RimeGetKeycodeByName("BackSpace")).locked == 0);
  TestT9PrefixUndo(harness, env);
  TestLearningSelection(harness, env);
  TestCandidateIdentity(harness, env);
  TestCandidateStateRefresh(harness, env);
  if (argc == 5)
    TestActorCandidateState(harness, env);
  native_exitRime(env, nullptr);
  clear_cache(env);
  env->DeleteLocalRef(rime_class);
  assert(vm->DestroyJavaVM() == JNI_OK);
  std::cout << "PASS: mapped formatting, Kotlin/JNI selection intent, full/double/T9 learning, preedit, edits, paging, lock undo and tail deletion\n";
}
