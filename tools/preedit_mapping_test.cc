#include "../app/src/main/cpp/jni/lime_jni.cc"
#include <cassert>
#include <iostream>
#include <rime/algo/calculus.h>

void rime_require_module_predict() {}

struct Snapshot {
  std::string raw;
  std::string text;
  std::vector<std::pair<int, int>> boundaries;
  int cursor;
  int display_cursor;
  int selection_start;
  int selection_end;
  int page;
  int locked;
};

class Harness {
 public:
  explicit Harness(JNIEnv* env) : env_(env) {}

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
    result.raw = Text(value, g_cache.snapshotClass, "rawInput");
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

  Snapshot Pick(const std::string& text) {
    auto context = rime::Service::instance().GetSession(g_session_id)->context();
    assert(context->HasMenu());
    auto menu = context->composition().back().menu;
    auto current = Read(build_snapshot_locked(env_, JNI_TRUE));
    for (size_t index = 0; index < 200; ++index) {
      auto candidate = menu->GetCandidateAt(index);
      if (!candidate) break;
      if (candidate->text() == text)
        return Read(native_selectRimeCandidateOnPageAndSnapshot(env_, nullptr,
            current.page, index / 15, index % 15));
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
};

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

int main(int argc, char** argv) {
  assert(argc == 4);
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
  Harness harness(env);
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
  harness.Type("t9_pinyin", "64426");
  auto locked = harness.Lock("ni");
  assert(locked.locked == 1);
  locked = harness.Lock("hao");
  assert(locked.locked == 2 && locked.text == "ni'hao");
  auto paged = harness.Page(locked.page, 1);
  assert(paged.raw == locked.raw && paged.text == locked.text && paged.boundaries == locked.boundaries);
  auto undone = harness.Key(RimeGetKeycodeByName("BackSpace"));
  assert(undone.raw == locked.raw && undone.locked == 1);
  auto tail = harness.Type("t9_pinyin", "64426");
  auto erased = harness.Key(RimeGetKeycodeByName("BackSpace"));
  assert(erased.raw == "6442" && erased.text == tail.text.substr(0, tail.text.size() - 1));
  harness.Type("t9_pinyin", "6");
  auto expanded = harness.Lock("ni");
  assert(expanded.text == "ni" && expanded.boundaries.size() == 2);
  native_exitRime(env, nullptr);
  clear_cache(env);
  assert(vm->DestroyJavaVM() == JNI_OK);
  std::cout << "PASS: mapped formatting, production JNI constructors, full/double/T9 preedit, selected text, edits, paging, lock undo and tail deletion\n";
}
