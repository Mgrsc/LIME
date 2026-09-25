// SPDX-FileCopyrightText: 2026 Bitfennec Authors & Upstream RIME community
// SPDX-License-Identifier: BSD-3-Clause

#include <jni.h>
#include <utf8.h>
#include <iterator>
#include <string_view>
#include <vector>
#include <rime_api.h>
#include <rime/key_table.h>
#include <rime/candidate.h>
#include <rime/algo/algebra.h>
#include <rime/context.h>
#include <rime/dict/dictionary.h>
#include <rime/dict/table.h>
#include <rime/gear/translator_commons.h>
#include <rime/menu.h>
#include <rime/schema.h>
#include <rime/service.h>
#include <rime/ticket.h>
#include <rime/lever/user_dict_manager.h>
#include "rime/gear/lime_user_dict_writes.h"
#include <opencc/Config.hpp>
#include <opencc/Converter.hpp>
#include <android/log.h>
#include <algorithm>
#include <chrono>
#include <filesystem>
#include <string>
#include <cstring>
#include <mutex>
#include <memory>
#include <unordered_map>
#include <map>

#define LOG_TAG "LimeEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

extern void rime_require_module_predict();

static RimeApi* g_rime_api = nullptr;
static RimeSessionId g_session_id = 0;
static std::string g_active_schema_id;
static bool g_rime_initialized = false;
static std::recursive_mutex g_rime_mutex;
static std::unordered_map<std::string, opencc::ConverterPtr> g_prewarmed_opencc;
static std::mutex g_opencc_mutex;
static std::string g_package_prefix = "org/bitfennec/lime/core";
static std::unique_ptr<rime::Dictionary> g_t9_dictionary;
static std::string g_t9_dictionary_schema_id;
// Keys are absolute byte offsets in the current raw input, like candidate spans.
static std::map<size_t, std::string> g_t9_locked_prefixes;
static std::string g_t9_pending_prefix_input;
static std::string g_t9_preedit_input;
static std::string g_t9_preedit_text;
static std::unique_ptr<rime::Projection> g_t9_spelling_projection;
static std::string g_t9_projection_schema_id;
static bool g_t9_projection_initialized = false;
static std::string g_t9_digit_keys;

static void clear_t9_preedit_cache_locked() {
    g_t9_pending_prefix_input.clear();
    g_t9_preedit_input.clear();
    g_t9_preedit_text.clear();
}

static void clear_t9_input_state_locked() {
    g_t9_locked_prefixes.clear();
    clear_t9_preedit_cache_locked();
}

static void reset_t9_session_locked() {
    clear_t9_input_state_locked();
    g_t9_spelling_projection.reset();
    g_t9_projection_schema_id.clear();
    g_t9_projection_initialized = false;
    g_t9_digit_keys.clear();
}

static rime::Projection* get_t9_spelling_projection_locked(const std::string& schema_id, rime::Config* config) {
    if (!config || schema_id.empty()) return nullptr;
    if (g_t9_projection_initialized && g_t9_projection_schema_id == schema_id) {
        return g_t9_spelling_projection.get();
    }
    g_t9_projection_schema_id = schema_id;
    g_t9_projection_initialized = true;
    g_t9_spelling_projection.reset();
    g_t9_digit_keys.clear();
    auto rules = config->GetList("speller/algebra");
    if (!rules || rules->size() == 0) {
        LOGW("T9 algebra rules missing for schema %s", schema_id.c_str());
        return nullptr;
    }
    // yagni: support the current final ASCII xlit; extend if the schema adds later transforms.
    auto rule = rules->GetValueAt(rules->size() - 1);
    if (!rule) {
        LOGW("Failed to get final T9 algebra rule for schema %s", schema_id.c_str());
        return nullptr;
    }
    const auto& formula = rule->str();
    if (formula.size() < 5 || formula.compare(0, 4, "xlit") != 0) {
        LOGW("Final T9 algebra rule is not xlit: %s", formula.c_str());
        return nullptr;
    }
    const char delimiter = formula[4];
    const auto middle = formula.find(delimiter, 5);
    if (middle == std::string::npos) {
        LOGW("Malformed T9 xlit delimiter in formula: %s", formula.c_str());
        return nullptr;
    }
    const auto end = formula.find(delimiter, middle + 1);
    if (end == std::string::npos || end + 1 != formula.size()) {
        LOGW("Malformed T9 xlit delimiter count in formula: %s", formula.c_str());
        return nullptr;
    }
    const auto alphabet = formula.substr(5, middle - 5);
    const auto digits = formula.substr(middle + 1, end - middle - 1);
    if (alphabet.size() != 26 || digits.size() != alphabet.size()) {
        LOGW("Malformed T9 xlit alphabet or digits: %s", formula.c_str());
        return nullptr;
    }
    std::string keys(26, '\0');
    for (size_t i = 0; i < alphabet.size(); ++i) {
        if (alphabet[i] < 'a' || alphabet[i] > 'z' || digits[i] < '2' || digits[i] > '9') {
            LOGW("Invalid char in T9 xlit: %c -> %c", alphabet[i], digits[i]);
            return nullptr;
        }
        const auto index = alphabet[i] - 'a';
        if (keys[index] != '\0') {
            LOGW("Duplicate alphabet char in T9 xlit: %c", alphabet[i]);
            return nullptr;
        }
        keys[index] = digits[i];
    }
    auto spelling_rules = rime::New<rime::ConfigList>();
    for (size_t i = 0; i + 1 < rules->size(); ++i) {
        spelling_rules->Append(rules->GetAt(i));
    }
    auto projection = std::make_unique<rime::Projection>();
    if (!projection->Load(spelling_rules)) {
        LOGW("Failed to load T9 spelling projection for schema %s", schema_id.c_str());
        return nullptr;
    }
    g_t9_digit_keys = std::move(keys);
    g_t9_spelling_projection = std::move(projection);
    return g_t9_spelling_projection.get();
}

struct RimeJniCache {
    jclass compClass = nullptr;
    jmethodID compInit = nullptr;

    jclass pageCandidateClass = nullptr;
    jmethodID pageCandidateInit = nullptr;

    jclass t9MetadataClass = nullptr;
    jmethodID t9MetadataInit = nullptr;

    jclass candidatePageClass = nullptr;
    jmethodID candidatePageInit = nullptr;

    jclass snapshotClass = nullptr;
    jmethodID snapshotInit = nullptr;

    jclass stringClass = nullptr;
};
static RimeJniCache g_cache;

// Rime/OpenCC use standard UTF-8, while JNI's *StringUTF APIs use Modified UTF-8.
static jstring new_utf8_string(JNIEnv* env, std::string_view text) {
    if (env->ExceptionCheck()) return nullptr;
    try {
        std::vector<jchar> utf16;
        utf8::utf8to16(text.begin(), text.end(), std::back_inserter(utf16));
        const jchar empty = 0;
        return env->NewString(utf16.empty() ? &empty : utf16.data(), utf16.size());
    } catch (const utf8::exception&) {
        jclass error = env->FindClass("java/lang/IllegalArgumentException");
        if (error) {
            env->ThrowNew(error, "Invalid UTF-8 from native engine");
            env->DeleteLocalRef(error);
        }
        return nullptr;
    }
}

static bool read_utf8_string(JNIEnv* env, jstring text, std::string& result) {
    if (env->ExceptionCheck()) return false;
    result.clear();
    const jchar* chars = env->GetStringChars(text, nullptr);
    if (!chars) return false;
    const jsize length = env->GetStringLength(text);
    bool valid = true;
    try {
        utf8::utf16to8(chars, chars + length, std::back_inserter(result));
    } catch (const utf8::exception&) {
        jclass error = env->FindClass("java/lang/IllegalArgumentException");
        if (error) {
            env->ThrowNew(error, "Invalid UTF-16 input");
            env->DeleteLocalRef(error);
        }
        valid = false;
    }
    env->ReleaseStringChars(text, chars);
    return valid;
}

// Match ClearNonConfirmedComposition on a copy, without changing the live session.
static std::string literal_composition_text(const rime::Composition& source,
                                            const std::string& input) {
    auto composition = source;
    while (!composition.empty() && composition.back().status < rime::Segment::kSelected) {
        composition.pop_back();
    }
    auto text = composition.GetCommitText();
    // The engine may have segmented only the input to the left of the caret.
    if (input.size() > composition.input().size()) {
        text += input.substr(composition.input().size());
    }
    return text;
}

static void shutdown_rime_locked() {
    g_t9_dictionary.reset();
    g_t9_dictionary_schema_id.clear();
    if (g_rime_api && g_session_id != 0) {
        g_rime_api->destroy_session(g_session_id);
    }
    g_session_id = 0;
    reset_t9_session_locked();
    g_active_schema_id.clear();
    if (g_rime_api && g_rime_initialized) {
        g_rime_api->finalize();
    }
    g_rime_api = nullptr;
    g_rime_initialized = false;
}

static rime::Dictionary* get_t9_dictionary_locked(rime::Session* session) {
    if (!session || !session->schema() ||
        session->schema()->schema_id() != "t9_pinyin") {
        return nullptr;
    }
    const std::string& schema_id = session->schema()->schema_id();
    if (g_t9_dictionary && g_t9_dictionary_schema_id == schema_id) {
        return g_t9_dictionary.get();
    }
    auto* component = rime::Dictionary::Require("dictionary");
    if (!component) {
        LOGW("T9 dictionary component is unavailable");
        return nullptr;
    }
    rime::Ticket ticket(session->schema(), "translator");
    auto dictionary = std::unique_ptr<rime::Dictionary>(component->Create(ticket));
    if (!dictionary || !dictionary->Load()) {
        LOGW("T9 dictionary is unavailable for schema %s", schema_id.c_str());
        return nullptr;
    }
    g_t9_dictionary_schema_id = schema_id;
    g_t9_dictionary = std::move(dictionary);
    return g_t9_dictionary.get();
}

static bool candidate_matches_locked_prefixes(
        rime::Dictionary* dictionary,
        const rime::an<rime::Candidate>& candidate,
        const std::map<size_t, std::string>& locked_prefixes,
        const std::string& input) {
    if (locked_prefixes.empty()) return true;
    if (!dictionary || !candidate) return false;
    auto phrase = rime::As<rime::Phrase>(
            rime::Candidate::GetGenuineCandidate(candidate));
    if (!phrase) return false;
    std::vector<std::string> syllables;
    if (!dictionary->Decode(phrase->matching_code(), &syllables) || syllables.empty()) {
        return false;
    }
    const auto spans = phrase->spans();
    for (const auto& [lock_start, locked_syllable] : locked_prefixes) {
        if (lock_start >= input.size()) return false;
        auto lock_end = input.find_first_of("' ", lock_start);
        if (lock_end == std::string::npos) lock_end = input.size();
        // Locks outside this candidate belong to preceding or following segments.
        if (lock_end <= candidate->start() || lock_start >= candidate->end()) continue;
        if (spans.Count() != syllables.size() || spans.end() > input.size()) return false;
        bool matched = false;
        size_t start = spans.start();
        for (const auto& syllable : syllables) {
            const size_t end = spans.NextStop(start);
            if (end <= start || end > input.size()) return false;
            size_t spelling_start = start;
            size_t spelling_end = end;
            while (spelling_start < spelling_end &&
                    (input[spelling_start] == '\'' || input[spelling_start] == ' ')) ++spelling_start;
            while (spelling_end > spelling_start &&
                    (input[spelling_end - 1] == '\'' || input[spelling_end - 1] == ' ')) --spelling_end;
            if (spelling_start == lock_start && spelling_end == lock_end &&
                    syllable == locked_syllable) {
                matched = true;
                break;
            }
            start = end;
        }
        if (!matched) return false;
    }
    return true;
}

static std::string decode_candidate_preedit(
        rime::Dictionary* dictionary,
        const rime::an<rime::Candidate>& candidate,
        const std::string& input,
        const std::string& schema_id,
        rime::Config* config) {
    if (!dictionary || !candidate || !config) return "";
    auto phrase = rime::As<rime::Phrase>(
            rime::Candidate::GetGenuineCandidate(candidate));
    if (!phrase) return "";
    std::vector<std::string> syllables;
    if (!dictionary->Decode(phrase->matching_code(), &syllables) || syllables.empty()) {
        return "";
    }
    const auto spans = phrase->spans();
    if (spans.Count() != syllables.size() || spans.end() > input.size()) return "";
    auto* spelling_projection = get_t9_spelling_projection_locked(schema_id, config);
    if (!spelling_projection) return "";
    std::string preedit;
    size_t start = spans.start();
    for (const auto& syllable : syllables) {
        const size_t end = spans.NextStop(start);
        if (end <= start || end > input.size()) return "";
        std::string raw = input.substr(start, end - start);
        raw.erase(std::remove_if(raw.begin(), raw.end(),
                [](char c) { return c == '\'' || c == ' '; }), raw.end());
        if (raw.empty()) return "";
        rime::Script spellings;
        spellings.AddSyllable(syllable);
        spelling_projection->Apply(&spellings);
        std::string selected;
        for (const auto& entry : spellings) {
            const auto& spelling = entry.first;
            if (spelling.find_first_not_of("abcdefghijklmnopqrstuvwxyz") != std::string::npos) continue;
            std::string encoded;
            for (char c : spelling) encoded += g_t9_digit_keys[c - 'a'];
            if (encoded == raw || spelling == raw) {
                selected = spelling;
                break;
            }
            if (selected.empty() && encoded.compare(0, raw.size(), raw) == 0) {
                selected = spelling.substr(0, raw.size());
            }
        }
        if (selected.empty()) return "";
        size_t spelling_start = start;
        while (spelling_start < end &&
                (input[spelling_start] == '\'' || input[spelling_start] == ' ')) ++spelling_start;
        const auto lock = g_t9_locked_prefixes.find(spelling_start);
        // A deliberate syllable selection may expand an abbreviation for display.
        if (lock != g_t9_locked_prefixes.end() && lock->second == syllable) {
            selected = syllable;
        }
        if (!preedit.empty()) preedit += "'";
        preedit += selected;
        start = end;
    }
    return preedit;
}

static std::vector<std::string> get_t9_prefix_options(
        rime::Dictionary* dictionary,
        const std::string& input) {
    if (!dictionary || input.empty()) return {};
    const size_t last_delimiter = input.find_last_of("' ");
    const std::string raw = (last_delimiter == std::string::npos)
            ? input
            : input.substr(last_delimiter + 1);
    if (raw.empty() || raw.find_first_not_of("23456789") != std::string::npos) {
        return {};
    }
    std::vector<std::string> options;
    const size_t min_length = std::min<size_t>(raw.size(), 6);
    for (size_t length = min_length; length >= 2; --length) {
        int spelling_id = 0;
        if (!dictionary->prism()->GetValue(raw.substr(0, length), &spelling_id)) {
            if (length == 2) break;
            continue;
        }
        auto accessor = dictionary->prism()->QuerySpelling(spelling_id);
        while (!accessor.exhausted()) {
            const auto syllable = dictionary->primary_table()->GetSyllableById(
                    accessor.syllable_id());
            if (!syllable.empty() &&
                    std::find(options.begin(), options.end(), syllable) == options.end()) {
                options.push_back(syllable);
                if (options.size() == 32) return options;
            }
            accessor.Next();
        }
        if (length == 2) break;
    }
    if (!options.empty()) return options;

    int spelling_id = 0;
    if (!dictionary->prism()->GetValue(raw.substr(0, 1), &spelling_id)) {
        return options;
    }
    auto accessor = dictionary->prism()->QuerySpelling(spelling_id);
    while (!accessor.exhausted()) {
        const auto syllable = dictionary->primary_table()->GetSyllableById(
                accessor.syllable_id());
        if (!syllable.empty() &&
                std::find(options.begin(), options.end(), syllable) == options.end()) {
            options.push_back(syllable);
            if (options.size() == 32) break;
        }
        accessor.Next();
    }
    return options;
}

static RimeSessionId get_session_locked() {
    if (!g_rime_api) return 0;
    if (g_session_id != 0 && g_rime_api->find_session(g_session_id)) {
        return g_session_id;
    }
    g_session_id = g_rime_api->create_session();
    reset_t9_session_locked();
    if (g_session_id == 0) {
        LOGE("Failed to create RIME session");
        return 0;
    }
    if (!g_active_schema_id.empty()) {
        g_rime_api->select_schema(g_session_id, g_active_schema_id.c_str());
    }
    return g_session_id;
}

static void on_rime_notification(void* context_object, RimeSessionId session_id, const char* message_type, const char* message_value) {
    LOGI("RIME Notify: type=%s, value=%s, sid=%lu",
         message_type ? message_type : "(null)",
         message_value ? message_value : "(null)",
         (unsigned long)session_id);
}

// 1. startupRime
static jboolean JNICALL native_startupRime(JNIEnv *env, jclass clazz, jobject context, jstring sharedDir, jstring userDir, jboolean fullCheck) {
    std::lock_guard<std::recursive_mutex> lock(g_rime_mutex);
    if (g_rime_api) {
        if (get_session_locked() != 0) {
            return JNI_TRUE;
        }
        shutdown_rime_locked();
        return JNI_FALSE;
    }

    g_rime_api = rime_get_api();
    if (!g_rime_api) {
        LOGE("Failed to get RIME API instance");
        return JNI_FALSE;
    }

    const char* c_shared = sharedDir ? env->GetStringUTFChars(sharedDir, nullptr) : "";
    const char* c_user = userDir ? env->GetStringUTFChars(userDir, nullptr) : "";
    auto release_strings = [&]() {
        if (sharedDir && c_shared) env->ReleaseStringUTFChars(sharedDir, c_shared);
        if (userDir && c_user) env->ReleaseStringUTFChars(userDir, c_user);
    };
    if ((sharedDir && !c_shared) || (userDir && !c_user)) {
        release_strings();
        g_rime_api = nullptr;
        g_session_id = 0;
        g_rime_initialized = false;
        return JNI_FALSE;
    }

    std::string prebuiltDir = std::string(c_shared) + "/build";
    std::string stagingDir = std::string(c_user) + "/build";

    RIME_STRUCT(RimeTraits, traits);
    traits.shared_data_dir = c_shared;
    traits.user_data_dir = c_user;
    traits.distribution_name = "Rime";
    traits.distribution_code_name = "lime";
    traits.distribution_version = "1.0.0";
    traits.app_name = "rime.lime";
    traits.prebuilt_data_dir = prebuiltDir.c_str();
    traits.staging_dir = stagingDir.c_str();

    LOGI("startupRime: shared='%s', user='%s', prebuilt='%s', fullCheck=%d",
         c_shared, c_user, prebuiltDir.c_str(), fullCheck);

    g_rime_api->setup(&traits);
    g_rime_api->set_notification_handler(on_rime_notification, nullptr);
    g_rime_api->initialize(&traits);
    g_rime_initialized = true;

    Bool maint = g_rime_api->start_maintenance(fullCheck ? True : False);
    LOGI("start_maintenance(fullCheck=%d) => %d", fullCheck, maint);
    if (maint) {
        g_rime_api->join_maintenance_thread();
        LOGI("RIME maintenance joined.");
    }

    release_strings();

    RimeSessionId sid = get_session_locked();
    if (sid == 0) {
        LOGE("Failed to create RIME session after initialization");
        shutdown_rime_locked();
        return JNI_FALSE;
    }

    LOGI("LIME Native Engine started successfully, session ID: %lu", (unsigned long)sid);

    RimeSchemaList list = {0};
    if (!g_rime_api->get_schema_list(&list) || list.size == 0) {
        LOGE("No valid RIME schemas available after initialization");
        if (list.list) g_rime_api->free_schema_list(&list);
        shutdown_rime_locked();
        return JNI_FALSE;
    }
    LOGI("Available schemas count: %zu", list.size);
    for (size_t i = 0; i < list.size; ++i) {
        LOGI("Schema [%zu]: id='%s', name='%s'", i, list.list[i].schema_id, list.list[i].name ? list.list[i].name : "");
    }
    g_rime_api->free_schema_list(&list);
    return JNI_TRUE;
}

// 2. exitRime
static void JNICALL native_exitRime(JNIEnv *env, jclass clazz) {
    std::lock_guard<std::recursive_mutex> lock(g_rime_mutex);
    shutdown_rime_locked();
}

// 3. setRimePageSize
static void JNICALL native_setRimePageSize(JNIEnv *env, jclass clazz, jint size) {
    std::lock_guard<std::recursive_mutex> lock(g_rime_mutex);
    if (!g_rime_api || size <= 0) return;
    RimeSessionId sid = get_session_locked();
    if (!sid) return;
    std::string page_size = std::to_string(size);
    g_rime_api->set_property(sid, "menu/page_size", page_size.c_str());
}

static jobject build_snapshot_locked(JNIEnv* env, jboolean handled) {
    if (!g_rime_api || !g_cache.compClass || !g_cache.compInit ||
        !g_cache.pageCandidateClass || !g_cache.pageCandidateInit ||
        !g_cache.candidatePageClass || !g_cache.candidatePageInit ||
        !g_cache.snapshotClass || !g_cache.snapshotInit ||
        !g_cache.t9MetadataClass || !g_cache.t9MetadataInit) {
        return nullptr;
    }
    RimeSessionId sid = get_session_locked();
    if (!sid) return nullptr;

    RIME_STRUCT(RimeContext, ctx);
    if (!g_rime_api->get_context(sid, &ctx)) return nullptr;

    RIME_STRUCT(RimeCommit, commit);
    const bool has_commit = g_rime_api->get_commit(sid, &commit);
    const char* raw_input = RIME_API_AVAILABLE(g_rime_api, get_input) ? g_rime_api->get_input(sid) : "";
    char schema_id[128] = {0};
    if (RIME_API_AVAILABLE(g_rime_api, get_current_schema)) {
        g_rime_api->get_current_schema(sid, schema_id, sizeof(schema_id) - 1);
        schema_id[sizeof(schema_id) - 1] = '\0';
    }

    std::string current_raw_input(raw_input ? raw_input : "");
    std::string fallback_locked_preedit;
    if (std::strcmp(schema_id, "t9_pinyin") != 0) {
        reset_t9_session_locked();
    } else if (current_raw_input.empty()) {
        clear_t9_input_state_locked();
    } else {
        auto session = rime::Service::instance().GetSession(sid);
        auto* dictionary = get_t9_dictionary_locked(session.get());
        for (auto it = g_t9_locked_prefixes.begin(); it != g_t9_locked_prefixes.end(); ) {
            if (it->first >= current_raw_input.size()) {
                it = g_t9_locked_prefixes.erase(it);
            } else if (dictionary && dictionary->prism() && dictionary->primary_table()) {
                const auto end = current_raw_input.find_first_of("' ", it->first);
                const auto seg_text = current_raw_input.substr(it->first,
                        end == std::string::npos ? end : end - it->first);
                int spelling_id = 0;
                bool valid = false;
                if (dictionary->prism()->GetValue(seg_text, &spelling_id)) {
                    auto accessor = dictionary->prism()->QuerySpelling(spelling_id);
                    while (!accessor.exhausted()) {
                        if (dictionary->primary_table()->GetSyllableById(accessor.syllable_id()) == it->second) {
                            valid = true;
                            break;
                        }
                        accessor.Next();
                    }
                }
                if (!valid) {
                    it = g_t9_locked_prefixes.erase(it);
                } else {
                    ++it;
                }
            } else {
                ++it;
            }
        }
        for (size_t pos = 0; pos < current_raw_input.size();) {
            const auto lock = g_t9_locked_prefixes.find(pos);
            if (lock == g_t9_locked_prefixes.end()) {
                fallback_locked_preedit += current_raw_input[pos++];
                continue;
            }
            if (!fallback_locked_preedit.empty() && fallback_locked_preedit.back() != '\'' &&
                    fallback_locked_preedit.back() != ' ') fallback_locked_preedit += "'";
            fallback_locked_preedit += lock->second;
            const auto end = current_raw_input.find_first_of("' ", pos);
            pos = end == std::string::npos ? current_raw_input.size() : end;
        }
    }

    if (!g_t9_locked_prefixes.empty()) {
        auto session = rime::Service::instance().GetSession(sid);
        auto* internal_context = session ? session->context() : nullptr;
        auto* dictionary = get_t9_dictionary_locked(session.get());
        if (dictionary && internal_context && internal_context->HasMenu() && ctx.menu.page_size > 0) {
            auto menu = internal_context->composition().back().menu;
            int page_no = ctx.menu.page_no;
            // yagni: scan empty pages synchronously; move filtering before paging if measured latency requires it.
            while (true) {
                auto page = rime::the<rime::Page>(menu->CreatePage(ctx.menu.page_size, page_no));
                if (!page || page->candidates.empty()) break;
                const bool matches = std::any_of(page->candidates.begin(), page->candidates.end(),
                        [&](const auto& candidate) {
                            return candidate_matches_locked_prefixes(
                                    dictionary, candidate, g_t9_locked_prefixes, current_raw_input);
                        });
                if (matches || page->is_last_page) break;
                ++page_no;
            }
            if (page_no != ctx.menu.page_no &&
                    internal_context->Highlight(static_cast<size_t>(page_no) * ctx.menu.page_size)) {
                g_rime_api->free_context(&ctx);
                if (!g_rime_api->get_context(sid, &ctx)) {
                    if (has_commit) g_rime_api->free_commit(&commit);
                    return nullptr;
                }
            }
        }
    }

    jstring schema = nullptr;
    jstring raw = nullptr;
    jstring preedit = nullptr;
    jstring literal = nullptr;
    jstring commit_text = nullptr;
    jobject composition = nullptr;
    jobjectArray candidates = nullptr;
    jobject page = nullptr;
    jobjectArray t9PrefixOptions = nullptr;
    jobject t9Metadata = nullptr;
    auto cleanup = [&]() {
        if (t9Metadata) env->DeleteLocalRef(t9Metadata);
        if (t9PrefixOptions) env->DeleteLocalRef(t9PrefixOptions);
        if (page) env->DeleteLocalRef(page);
        if (candidates) env->DeleteLocalRef(candidates);
        if (composition) env->DeleteLocalRef(composition);
        if (commit_text) env->DeleteLocalRef(commit_text);
        if (preedit) env->DeleteLocalRef(preedit);
        if (literal) env->DeleteLocalRef(literal);
        if (raw) env->DeleteLocalRef(raw);
        if (schema) env->DeleteLocalRef(schema);
        if (has_commit) g_rime_api->free_commit(&commit);
        g_rime_api->free_context(&ctx);
    };

    schema = new_utf8_string(env, schema_id);
    raw = new_utf8_string(env, raw_input ? raw_input : "");
    preedit = new_utf8_string(env, ctx.composition.preedit ? ctx.composition.preedit : "");
    auto session = rime::Service::instance().GetSession(sid);
    literal = new_utf8_string(env, session && session->context()
            ? literal_composition_text(session->context()->composition(), raw_input ? raw_input : "")
            : (raw_input ? raw_input : ""));
    if (!schema || !raw || !preedit || !literal) {
        cleanup();
        return nullptr;
    }
    const jint comp_cursor_pos = (session && session->context())
            ? (jint)session->context()->caret_pos()
            : (jint)ctx.composition.cursor_pos;
    const jint comp_length = (session && session->context())
            ? (jint)session->context()->input().length()
            : (jint)ctx.composition.length;
    composition = env->NewObject(g_cache.compClass, g_cache.compInit,
        comp_length,
        comp_cursor_pos,
        (jint)ctx.composition.sel_start,
        (jint)ctx.composition.sel_end,
        preedit, literal);
    if (!composition) {
        cleanup();
        return nullptr;
    }

    const int candidate_count = (ctx.menu.num_candidates > 0 && ctx.menu.candidates)
        ? ctx.menu.num_candidates
        : 0;
    std::string t9_preedit = current_raw_input == g_t9_preedit_input
            ? g_t9_preedit_text : "";
    std::vector<std::string> t9_prefix_options;
    std::vector<bool> candidate_matches(candidate_count, g_t9_locked_prefixes.empty());
    if (std::strcmp(schema_id, "t9_pinyin") == 0) {
        auto session = rime::Service::instance().GetSession(sid);
        auto* dictionary = get_t9_dictionary_locked(session.get());
        auto* internal_context = session ? session->context() : nullptr;
        if (dictionary && internal_context && internal_context->HasMenu()) {
            auto& segment = internal_context->composition().back();
            const int page_size = ctx.menu.page_size > 0 ? ctx.menu.page_size : 1;
            auto internal_page = rime::the<rime::Page>(
                    segment.menu->CreatePage(page_size, ctx.menu.page_no));
            if (internal_page) {
                const size_t count = std::min(
                        internal_page->candidates.size(), candidate_matches.size());
                for (size_t index = 0; index < count; ++index) {
                    const auto& candidate = internal_page->candidates[index];
                    const bool matches = candidate_matches_locked_prefixes(
                            dictionary, candidate, g_t9_locked_prefixes, current_raw_input);
                    candidate_matches[index] = matches;
                    if (matches && t9_preedit.empty()) {
                        const auto& input = internal_context->input();
                        auto decoded = decode_candidate_preedit(
                                dictionary, candidate, input, schema_id,
                                session && session->schema() ? session->schema()->config() : nullptr);
                        if (!decoded.empty() && candidate->start() == segment.start &&
                            candidate->end() >= candidate->start() &&
                            candidate->end() <= input.size()) {
                            const auto native_preedit = internal_context->composition().GetPreedit(
                                    input, internal_context->caret_pos(), "");
                            // Candidate spans count input bytes, not decoded pinyin letters.
                            t9_preedit = native_preedit.text.substr(0, native_preedit.sel_start) +
                                    decoded + input.substr(candidate->end());
                        }
                    }
                }
            }
        }
        const size_t prefix_start = internal_context && !internal_context->composition().empty()
                ? internal_context->composition().back().start : 0;
        if (prefix_start <= current_raw_input.size()) {
            t9_prefix_options = get_t9_prefix_options(dictionary, current_raw_input.substr(prefix_start));
        }
    }
    if (t9_preedit.empty()) {
        t9_preedit = fallback_locked_preedit;
    }
    if (std::strcmp(schema_id, "t9_pinyin") == 0) {
        g_t9_preedit_input = current_raw_input;
        g_t9_preedit_text = t9_preedit;
    }
    std::vector<int> visible_candidate_indices;
    visible_candidate_indices.reserve(candidate_count);
    for (int index = 0; index < candidate_count; ++index) {
        if (candidate_matches[index]) {
            visible_candidate_indices.push_back(index);
        }
    }
    candidates = env->NewObjectArray(
            static_cast<jsize>(visible_candidate_indices.size()),
            g_cache.pageCandidateClass,
            nullptr);
    if (!candidates) {
        cleanup();
        return nullptr;
    }
    for (size_t visible_index = 0;
         visible_index < visible_candidate_indices.size();
         ++visible_index) {
        const int index = visible_candidate_indices[visible_index];
        const char* candidate_text = ctx.menu.candidates[index].text ? ctx.menu.candidates[index].text : "";
        const char* comment_text = ctx.menu.candidates[index].comment ? ctx.menu.candidates[index].comment : "";
        jstring text = new_utf8_string(env, candidate_text);
        jstring comment = new_utf8_string(env, comment_text);
        if (!text || !comment) {
            if (text) env->DeleteLocalRef(text);
            if (comment) env->DeleteLocalRef(comment);
            cleanup();
            return nullptr;
        }
        jobject candidate = env->NewObject(g_cache.pageCandidateClass, g_cache.pageCandidateInit,
            (jint)index, text, comment);
        env->DeleteLocalRef(text);
        env->DeleteLocalRef(comment);
        if (!candidate) {
            cleanup();
            return nullptr;
        }
        env->SetObjectArrayElement(candidates, static_cast<jsize>(visible_index), candidate);
        env->DeleteLocalRef(candidate);
        if (env->ExceptionCheck()) {
            cleanup();
            return nullptr;
        }
    }

    int highlighted_index = 0;
    const auto highlighted = std::find(
            visible_candidate_indices.begin(),
            visible_candidate_indices.end(),
            ctx.menu.highlighted_candidate_index);
    if (highlighted != visible_candidate_indices.end()) {
        highlighted_index = static_cast<int>(
                std::distance(visible_candidate_indices.begin(), highlighted));
    }
    page = env->NewObject(g_cache.candidatePageClass, g_cache.candidatePageInit,
        (jint)ctx.menu.page_no,
        (jint)ctx.menu.page_size,
        (jboolean)(!visible_candidate_indices.empty() && ctx.menu.page_no > 0 ? JNI_TRUE : JNI_FALSE),
        (jboolean)(!visible_candidate_indices.empty() && !ctx.menu.is_last_page ? JNI_TRUE : JNI_FALSE),
        (jint)highlighted_index,
        candidates);
    if (!page) {
        cleanup();
        return nullptr;
    }

    if (has_commit && commit.text && commit.text[0] != '\0') {
        commit_text = new_utf8_string(env, commit.text);
        if (!commit_text) {
            cleanup();
            return nullptr;
        }
    }
    if (std::strcmp(schema_id, "t9_pinyin") == 0) {
        t9PrefixOptions = env->NewObjectArray(
                static_cast<jsize>(t9_prefix_options.size()), g_cache.stringClass, nullptr);
        if (!t9PrefixOptions) {
            cleanup();
            return nullptr;
        }
        for (size_t index = 0; index < t9_prefix_options.size(); ++index) {
            jstring option = new_utf8_string(env, t9_prefix_options[index].c_str());
            if (!option) {
                cleanup();
                return nullptr;
            }
            env->SetObjectArrayElement(t9PrefixOptions, static_cast<jsize>(index), option);
            env->DeleteLocalRef(option);
            if (env->ExceptionCheck()) {
                cleanup();
                return nullptr;
            }
        }
        jstring preeditText = new_utf8_string(env, t9_preedit.c_str());
        if (!preeditText) {
            cleanup();
            return nullptr;
        }
        const jint locked_count = static_cast<jint>(g_t9_locked_prefixes.size());
        t9Metadata = env->NewObject(
                g_cache.t9MetadataClass, g_cache.t9MetadataInit, preeditText, t9PrefixOptions, locked_count);
        env->DeleteLocalRef(preeditText);
        if (!t9Metadata) {
            cleanup();
            return nullptr;
        }
    }
    jobject snapshot = env->NewObject(g_cache.snapshotClass, g_cache.snapshotInit,
        handled, schema, raw, composition, commit_text, page, t9Metadata);
    if (!snapshot || env->ExceptionCheck()) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        cleanup();
        return nullptr;
    }
    cleanup();
    return snapshot;
}

static jobject JNICALL native_processRimeKeyAndSnapshot(JNIEnv *env, jclass clazz, jint keyCode, jint mask) {
#ifndef NDEBUG
    const bool trace = __android_log_is_loggable(ANDROID_LOG_DEBUG, "LimeInputTrace", ANDROID_LOG_INFO);
    using Clock = std::chrono::steady_clock;
    const auto started = trace ? Clock::now() : Clock::time_point{};
#endif
    std::lock_guard<std::recursive_mutex> lock(g_rime_mutex);
#ifndef NDEBUG
    const auto locked = trace ? Clock::now() : Clock::time_point{};
#endif
    if (!g_rime_api) return nullptr;
    RimeSessionId sid = get_session_locked();
    if (!sid) return nullptr;
#ifndef NDEBUG
    const auto prepared = trace ? Clock::now() : Clock::time_point{};
#endif
    static const int backspace_keycode = RimeGetKeycodeByName("BackSpace");
    Bool handled = False;
    auto session = rime::Service::instance().GetSession(sid);
    auto* context = session ? session->context() : nullptr;
    // Like ExpressEditor, undo a recent selection before deleting input.
    // Paging preserves this opportunity; another input/edit consumes it.
    if (context && !g_t9_pending_prefix_input.empty() && mask == 0 &&
            keyCode == backspace_keycode &&
            context->input() == g_t9_pending_prefix_input &&
            !g_t9_locked_prefixes.empty()) {
        g_t9_locked_prefixes.erase(std::prev(g_t9_locked_prefixes.end()));
        clear_t9_preedit_cache_locked();
        context->Highlight(0);
        handled = True;
    }
    g_t9_pending_prefix_input.clear();
    if (!handled) {
        const bool tail_backspace = context && mask == 0 &&
                keyCode == backspace_keycode &&
                context->caret_pos() == context->input().size() &&
                context->input() == g_t9_preedit_input;
        handled = g_rime_api->process_key(sid, keyCode, mask);
        bool retained = false;
        if (tail_backspace && handled && g_t9_digit_keys.size() == 26 &&
                !g_t9_preedit_text.empty() &&
                context->input().size() + 1 == g_t9_preedit_input.size() &&
                g_t9_preedit_input.compare(0, context->input().size(), context->input()) == 0) {
            // Retain the chosen spelling only when it maps one-to-one to the keys.
            std::string encoded;
            for (char c : g_t9_preedit_text) {
                if (c >= 'a' && c <= 'z') encoded += g_t9_digit_keys[c - 'a'];
                else if (c != '\'' && c != ' ') encoded += c;
            }
            std::string raw = g_t9_preedit_input;
            raw.erase(std::remove_if(raw.begin(), raw.end(),
                    [](char c) { return c == '\'' || c == ' '; }), raw.end());
            if (encoded == raw && g_t9_preedit_input.back() != '\'' && g_t9_preedit_input.back() != ' ') {
                while (!g_t9_preedit_text.empty() && (g_t9_preedit_text.back() == '\'' || g_t9_preedit_text.back() == ' '))
                    g_t9_preedit_text.pop_back();
                if (!g_t9_preedit_text.empty()) g_t9_preedit_text.pop_back();
                while (!g_t9_preedit_text.empty() && (g_t9_preedit_text.back() == '\'' || g_t9_preedit_text.back() == ' '))
                    g_t9_preedit_text.pop_back();
                g_t9_preedit_input = context->input();
                retained = true;
            }
        }
        if (!retained) {
            g_t9_preedit_input.clear();
            g_t9_preedit_text.clear();
        }
    }
#ifndef NDEBUG
    const auto processed = trace ? Clock::now() : Clock::time_point{};
#endif
    jobject snapshot = build_snapshot_locked(env, handled ? JNI_TRUE : JNI_FALSE);
#ifndef NDEBUG
    if (trace) {
        const auto finished = Clock::now();
        const auto milliseconds = [](Clock::duration duration) {
            return std::chrono::duration<double, std::milli>(duration).count();
        };
        // Emitted on the same thread immediately before the enclosing rime_processed event.
        // Snapshot time includes lazy candidate evaluation, not just JNI object allocation.
        __android_log_print(ANDROID_LOG_DEBUG, "LimeInputTrace",
            "{\"event\":\"ime.native_key_processed\",\"lock_wait_ms\":%.3f,"
            "\"session_prepare_ms\":%.3f,\"process_key_ms\":%.3f,"
            "\"snapshot_ms\":%.3f,\"duration_ms\":%.3f,\"snapshot_ok\":%s}",
            milliseconds(locked - started), milliseconds(prepared - locked),
            milliseconds(processed - prepared), milliseconds(finished - processed),
            milliseconds(finished - started), snapshot ? "true" : "false");
    }
#endif
    return snapshot;
}

static jobject JNICALL native_clearRimeCompositionAndSnapshot(JNIEnv *env, jclass clazz) {
    std::lock_guard<std::recursive_mutex> lock(g_rime_mutex);
    if (!g_rime_api) return nullptr;
    RimeSessionId sid = get_session_locked();
    if (!sid) return nullptr;
    g_rime_api->clear_composition(sid);
    clear_t9_input_state_locked();
    return build_snapshot_locked(env, JNI_TRUE);
}

static void JNICALL native_setUserDictWritesEnabled(JNIEnv *env, jclass clazz, jboolean enabled) {
    (void)env;
    (void)clazz;
    std::lock_guard<std::recursive_mutex> lock(g_rime_mutex);
    lime::set_user_dict_writes_enabled(enabled == JNI_TRUE);
}

static void JNICALL native_setRimeOption(JNIEnv *env, jclass clazz, jstring option, jboolean value) {
    std::lock_guard<std::recursive_mutex> lock(g_rime_mutex);
    if (!g_rime_api || !option) return;
    RimeSessionId sid = get_session_locked();
    if (!sid) return;
    const char* c_option = env->GetStringUTFChars(option, nullptr);
    if (!c_option) return;
    g_rime_api->set_option(sid, c_option, value == JNI_TRUE);
    env->ReleaseStringUTFChars(option, c_option);
}

static jboolean JNICALL native_getRimeOption(JNIEnv *env, jclass clazz, jstring option) {
    std::lock_guard<std::recursive_mutex> lock(g_rime_mutex);
    if (!g_rime_api || !option) return JNI_FALSE;
    RimeSessionId sid = get_session_locked();
    if (!sid) return JNI_FALSE;
    const char* c_option = env->GetStringUTFChars(option, nullptr);
    if (!c_option) return JNI_FALSE;
    Bool val = g_rime_api->get_option(sid, c_option);
    env->ReleaseStringUTFChars(option, c_option);
    return val ? JNI_TRUE : JNI_FALSE;
}

static jstring JNICALL native_getCurrentRimeSchema(JNIEnv *env, jclass clazz) {
    std::lock_guard<std::recursive_mutex> lock(g_rime_mutex);
    if (!g_rime_api) return new_utf8_string(env, "");
    RimeSessionId sid = get_session_locked();
    if (!sid) return new_utf8_string(env, "");
    char schema_id[128] = {0};
    if (g_rime_api->get_current_schema(sid, schema_id, sizeof(schema_id) - 1)) {
        schema_id[sizeof(schema_id) - 1] = '\0';
        return new_utf8_string(env, schema_id);
    }
    return new_utf8_string(env, "");
}

static jboolean JNICALL native_selectRimeSchema(JNIEnv *env, jclass clazz, jstring schemaId) {
    std::lock_guard<std::recursive_mutex> lock(g_rime_mutex);
    if (!g_rime_api || !schemaId) return JNI_FALSE;
    RimeSessionId sid = get_session_locked();
    if (!sid) return JNI_FALSE;
    const char* c_schemaId = env->GetStringUTFChars(schemaId, nullptr);
    if (!c_schemaId) return JNI_FALSE;
    Bool ret = g_rime_api->select_schema(sid, c_schemaId);
    LOGI("select_schema(sid=%lu) => %d", (unsigned long)sid, ret);
    if (ret) {
        g_active_schema_id = c_schemaId;
        reset_t9_session_locked();
    }
    env->ReleaseStringUTFChars(schemaId, c_schemaId);
    return ret ? JNI_TRUE : JNI_FALSE;
}

static jobject JNICALL native_selectRimeCandidateOnPageAndSnapshot(
    JNIEnv *env,
    jclass clazz,
    jint expected_current_page_no,
    jint target_page_no,
    jint target_page_index
) {
    std::lock_guard<std::recursive_mutex> lock(g_rime_mutex);
    if (!g_rime_api || expected_current_page_no < 0 || target_page_no < 0 || target_page_index < 0) {
        return nullptr;
    }
    RimeSessionId sid = get_session_locked();
    if (!sid) return nullptr;

    RIME_STRUCT(RimeContext, context);
    if (!g_rime_api->get_context(sid, &context)) return nullptr;
    const int current_page_no = context.menu.page_no;
    g_rime_api->free_context(&context);
    if (current_page_no != expected_current_page_no) return nullptr;

    const int direction = target_page_no > current_page_no ? 1 : -1;
    for (int page_no = current_page_no; page_no != target_page_no; page_no += direction) {
        const int key_code = RimeGetKeycodeByName(direction > 0 ? "Page_Down" : "Page_Up");
        if (key_code <= 0 || !g_rime_api->process_key(sid, key_code, 0)) return nullptr;

        RIME_STRUCT(RimeContext, moved_context);
        if (!g_rime_api->get_context(sid, &moved_context)) return nullptr;
        const bool moved_to_expected_page = moved_context.menu.page_no == page_no + direction;
        g_rime_api->free_context(&moved_context);
        if (!moved_to_expected_page) return nullptr;
    }

    RIME_STRUCT(RimeContext, target_context);
    if (!g_rime_api->get_context(sid, &target_context)) return nullptr;
    const bool index_valid = target_context.menu.page_no == target_page_no &&
        target_context.menu.candidates && target_page_index < target_context.menu.num_candidates;
    g_rime_api->free_context(&target_context);
    if (!index_valid) return nullptr;

    clear_t9_preedit_cache_locked();
    const Bool handled = g_rime_api->select_candidate_on_current_page(sid, static_cast<size_t>(target_page_index));
    return build_snapshot_locked(env, handled ? JNI_TRUE : JNI_FALSE);
}

static jobject JNICALL native_deleteRimeCandidateOnPageAndSnapshot(
    JNIEnv *env,
    jclass clazz,
    jint expected_current_page_no,
    jint target_page_no,
    jint target_page_index
) {
    std::lock_guard<std::recursive_mutex> lock(g_rime_mutex);
    if (!g_rime_api || expected_current_page_no < 0 || target_page_no < 0 || target_page_index < 0) {
        return nullptr;
    }
    RimeSessionId sid = get_session_locked();
    if (!sid) return nullptr;

    RIME_STRUCT(RimeContext, context);
    if (!g_rime_api->get_context(sid, &context)) return nullptr;
    const int current_page_no = context.menu.page_no;
    g_rime_api->free_context(&context);
    if (current_page_no != expected_current_page_no) return nullptr;

    const int direction = target_page_no > current_page_no ? 1 : -1;
    for (int page_no = current_page_no; page_no != target_page_no; page_no += direction) {
        const int key_code = RimeGetKeycodeByName(direction > 0 ? "Page_Down" : "Page_Up");
        if (key_code <= 0 || !g_rime_api->process_key(sid, key_code, 0)) return nullptr;

        RIME_STRUCT(RimeContext, moved_context);
        if (!g_rime_api->get_context(sid, &moved_context)) return nullptr;
        const bool moved_to_expected_page = moved_context.menu.page_no == page_no + direction;
        g_rime_api->free_context(&moved_context);
        if (!moved_to_expected_page) return nullptr;
    }

    RIME_STRUCT(RimeContext, target_context);
    if (!g_rime_api->get_context(sid, &target_context)) return nullptr;
    const bool index_valid = target_context.menu.page_no == target_page_no &&
        target_context.menu.candidates && target_page_index < target_context.menu.num_candidates;
    g_rime_api->free_context(&target_context);
    if (!index_valid) return nullptr;

    clear_t9_preedit_cache_locked();
    const Bool handled = g_rime_api->delete_candidate_on_current_page(
        sid, static_cast<size_t>(target_page_index));
    return build_snapshot_locked(env, handled ? JNI_TRUE : JNI_FALSE);
}

static jobject JNICALL native_moveRimeCandidatePageAndSnapshot(
    JNIEnv *env,
    jclass clazz,
    jint expectedPageNo,
    jint direction
) {
    std::lock_guard<std::recursive_mutex> lock(g_rime_mutex);
    if (!g_rime_api || expectedPageNo < 0 || (direction != -1 && direction != 1)) return nullptr;
    RimeSessionId sid = get_session_locked();
    if (!sid) return nullptr;

    RIME_STRUCT(RimeContext, context);
    if (!g_rime_api->get_context(sid, &context)) return nullptr;
    const bool page_matches = context.menu.page_no == expectedPageNo;
    const bool can_move = direction > 0 ? !context.menu.is_last_page : context.menu.page_no > 0;
    g_rime_api->free_context(&context);
    if (!page_matches || !can_move) return nullptr;

    const int key_code = RimeGetKeycodeByName(direction > 0 ? "Page_Down" : "Page_Up");
    if (key_code <= 0) return nullptr;
    const Bool handled = g_rime_api->process_key(sid, key_code, 0);
    return build_snapshot_locked(env, handled ? JNI_TRUE : JNI_FALSE);
}

static jobject JNICALL native_snapshotRimeState(JNIEnv *env, jclass clazz) {
    std::lock_guard<std::recursive_mutex> lock(g_rime_mutex);
    return build_snapshot_locked(env, JNI_TRUE);
}

static jobject JNICALL native_selectT9PrefixAndSnapshot(
    JNIEnv *env,
    jclass clazz,
    jstring prefix
) {
    std::lock_guard<std::recursive_mutex> lock(g_rime_mutex);
    if (!g_rime_api || !prefix) return nullptr;
    RimeSessionId sid = get_session_locked();
    if (!sid) return nullptr;
    const char* c_prefix = env->GetStringUTFChars(prefix, nullptr);
    if (!c_prefix) return nullptr;
    const std::string selected_prefix(c_prefix);
    env->ReleaseStringUTFChars(prefix, c_prefix);
    if (selected_prefix.empty()) return nullptr;

    char schema_id[128] = {0};
    if (!g_rime_api->get_current_schema(sid, schema_id, sizeof(schema_id) - 1) ||
        std::strcmp(schema_id, "t9_pinyin") != 0) {
        return nullptr;
    }

    auto session = rime::Service::instance().GetSession(sid);
    auto* internal_context = session ? session->context() : nullptr;
    auto* dictionary = get_t9_dictionary_locked(session.get());
    if (internal_context && dictionary && dictionary->prism() && dictionary->primary_table()) {
        const std::string raw_input = internal_context->input();
        const size_t last_delim = raw_input.find_last_of("' ");
        const size_t composition_start = internal_context->composition().empty()
                ? 0 : internal_context->composition().back().start;
        const size_t segment_start = std::max(composition_start,
                last_delim == std::string::npos ? size_t{0} : last_delim + 1);
        if (segment_start > raw_input.size()) return nullptr;
        const std::string raw = raw_input.substr(segment_start);
        if (!raw.empty()) {
            size_t matched_digit_length = 0;
            const size_t min_length = std::min<size_t>(raw.size(), 6);
            for (size_t length = min_length; length >= 1; --length) {
                int spelling_id = 0;
                if (!dictionary->prism()->GetValue(raw.substr(0, length), &spelling_id)) {
                    continue;
                }
                auto accessor = dictionary->prism()->QuerySpelling(spelling_id);
                while (!accessor.exhausted()) {
                    const auto syllable = dictionary->primary_table()->GetSyllableById(
                            accessor.syllable_id());
                    if (syllable == selected_prefix) {
                        matched_digit_length = length;
                        break;
                    }
                    accessor.Next();
                }
                if (matched_digit_length > 0) break;
            }

            if (matched_digit_length > 0) {
                const size_t split_pos = segment_start + matched_digit_length;
                std::string new_input;
                if (split_pos < raw_input.length()) {
                    if (raw_input[split_pos] != '\'' && raw_input[split_pos] != ' ') {
                        new_input = raw_input.substr(0, split_pos) + "'" + raw_input.substr(split_pos);
                    }
                }
                if (!new_input.empty()) {
                    internal_context->set_input(new_input);
                }
                g_t9_preedit_input.clear();
                g_t9_preedit_text.clear();
                g_t9_locked_prefixes[segment_start] = selected_prefix;
                g_t9_pending_prefix_input = internal_context->input();
            }
        }
    }
    if (internal_context && internal_context->HasMenu()) {
        internal_context->Highlight(0);
    }

    return build_snapshot_locked(env, JNI_TRUE);
}

// 14. getRimeKeycodeByName
static jint JNICALL native_getRimeKeycodeByName(JNIEnv *env, jclass clazz, jstring name) {
    std::lock_guard<std::recursive_mutex> lock(g_rime_mutex);
    if (!name) return 0;
    const char* c_name = env->GetStringUTFChars(name, nullptr);
    if (!c_name) return 0;
    int keycode = RimeGetKeycodeByName(c_name);
    env->ReleaseStringUTFChars(name, c_name);
    return keycode;
}

// 15. prewarmOpencc
static void JNICALL native_prewarmOpencc(JNIEnv *env, jclass clazz, jstring configPath) {
    if (!configPath) return;
    const char* c_path = env->GetStringUTFChars(configPath, nullptr);
    if (!c_path) return;
    std::string path_str(c_path);
    env->ReleaseStringUTFChars(configPath, c_path);

    std::lock_guard<std::mutex> lock(g_opencc_mutex);
    if (g_prewarmed_opencc.find(path_str) != g_prewarmed_opencc.end()) {
        return;
    }
    try {
        opencc::Config config;
        opencc::ConverterPtr converter = config.NewFromFile(path_str);
        if (converter) {
            converter->Convert("的");
            g_prewarmed_opencc[path_str] = converter;
            LOGI("Prewarmed OpenCC configuration: %s", path_str.c_str());
        }
    } catch (const std::exception& e) {
        LOGW("OpenCC prewarm failed for %s: %s", path_str.c_str(), e.what());
    } catch (...) {
        LOGW("OpenCC prewarm failed for %s with unknown error", path_str.c_str());
    }
}

// 16. openccConvert
static jstring JNICALL native_openccConvert(JNIEnv *env, jclass clazz, jstring configPath, jstring text) {
    if (!configPath || !text) return text;
    const char* c_path = env->GetStringUTFChars(configPath, nullptr);
    std::string text_str;
    if (!c_path || !read_utf8_string(env, text, text_str)) {
        if (c_path) env->ReleaseStringUTFChars(configPath, c_path);
        return text;
    }
    std::string path_str(c_path);
    env->ReleaseStringUTFChars(configPath, c_path);

    std::lock_guard<std::mutex> lock(g_opencc_mutex);
    auto it = g_prewarmed_opencc.find(path_str);
    if (it != g_prewarmed_opencc.end() && it->second) {
        try {
            std::string converted = it->second->Convert(text_str);
            return new_utf8_string(env, converted);
        } catch (...) {
            return text;
        }
    }
    try {
        opencc::Config config;
        opencc::ConverterPtr converter = config.NewFromFile(path_str);
        if (converter) {
            g_prewarmed_opencc[path_str] = converter;
            std::string converted = converter->Convert(text_str);
            return new_utf8_string(env, converted);
        }
    } catch (...) {}
    return text;
}

// Helper to drain all sessions and clear T9 cache to release LevelDB write lock
static void drain_all_sessions_locked() {
    if (g_session_id != 0 && g_rime_api) {
        g_rime_api->destroy_session(g_session_id);
        g_session_id = 0;
    }
    reset_t9_session_locked();
    rime::Service::instance().CleanupAllSessions();
    g_t9_dictionary.reset();
    g_t9_dictionary_schema_id.clear();
}

// 17. exportUserDict
static jint JNICALL native_exportUserDict(JNIEnv *env, jclass clazz, jstring dictName, jstring outputPath) {
    if (!dictName || !outputPath) return -1;
    const char* c_dict_name = env->GetStringUTFChars(dictName, nullptr);
    const char* c_output_path = env->GetStringUTFChars(outputPath, nullptr);
    if (!c_dict_name || !c_output_path) {
        if (c_dict_name) env->ReleaseStringUTFChars(dictName, c_dict_name);
        if (c_output_path) env->ReleaseStringUTFChars(outputPath, c_output_path);
        return -1;
    }
    std::string dict_name_str(c_dict_name);
    std::string output_path_str(c_output_path);
    env->ReleaseStringUTFChars(dictName, c_dict_name);
    env->ReleaseStringUTFChars(outputPath, c_output_path);

    std::lock_guard<std::recursive_mutex> lock(g_rime_mutex);
    if (!g_rime_api || !g_rime_initialized) {
        LOGE("exportUserDict: RIME engine not initialized");
        return -1;
    }

    // Drain sessions to release LevelDB write lock
    drain_all_sessions_locked();

    int count = -1;
    try {
        rime::Deployer& deployer(rime::Service::instance().deployer());
        rime::UserDictManager manager(&deployer);
        count = manager.Export(dict_name_str, rime::path(output_path_str));
        LOGI("exportUserDict: exported %d entries for %s", count, dict_name_str.c_str());
    } catch (const std::exception& e) {
        LOGE("exportUserDict failed for %s: %s", dict_name_str.c_str(), e.what());
    } catch (...) {
        LOGE("exportUserDict failed for %s with unknown exception", dict_name_str.c_str());
    }
    return count;
}

// 18. importUserDict
static jint JNICALL native_importUserDict(JNIEnv *env, jclass clazz, jstring dictName, jstring inputPath) {
    if (!dictName || !inputPath) return -1;
    const char* c_dict_name = env->GetStringUTFChars(dictName, nullptr);
    const char* c_input_path = env->GetStringUTFChars(inputPath, nullptr);
    if (!c_dict_name || !c_input_path) {
        if (c_dict_name) env->ReleaseStringUTFChars(dictName, c_dict_name);
        if (c_input_path) env->ReleaseStringUTFChars(inputPath, c_input_path);
        return -1;
    }
    std::string dict_name_str(c_dict_name);
    std::string input_path_str(c_input_path);
    env->ReleaseStringUTFChars(dictName, c_dict_name);
    env->ReleaseStringUTFChars(inputPath, c_input_path);

    std::lock_guard<std::recursive_mutex> lock(g_rime_mutex);
    if (!g_rime_api || !g_rime_initialized) {
        LOGE("importUserDict: RIME engine not initialized");
        return -1;
    }

    // Drain sessions to release LevelDB lock
    drain_all_sessions_locked();

    int count = -1;
    try {
        rime::Deployer& deployer(rime::Service::instance().deployer());
        rime::UserDictManager manager(&deployer);
        count = manager.Import(dict_name_str, rime::path(input_path_str));
        LOGI("importUserDict: imported %d entries for %s", count, dict_name_str.c_str());
    } catch (const std::exception& e) {
        LOGE("importUserDict failed for %s: %s", dict_name_str.c_str(), e.what());
    } catch (...) {
        LOGE("importUserDict failed for %s with unknown exception", dict_name_str.c_str());
    }
    return count;
}

// 19. getUserDictList
static jobject JNICALL native_getUserDictList(JNIEnv *env, jclass clazz) {
    jclass arrayListClass = env->FindClass("java/util/ArrayList");
    jmethodID initMethod = env->GetMethodID(arrayListClass, "<init>", "()V");
    jmethodID addMethod = env->GetMethodID(arrayListClass, "add", "(Ljava/lang/Object;)Z");
    jobject listObj = env->NewObject(arrayListClass, initMethod);

    std::lock_guard<std::recursive_mutex> lock(g_rime_mutex);
    if (!g_rime_api || !g_rime_initialized) {
        return listObj;
    }

    try {
        rime::Deployer& deployer(rime::Service::instance().deployer());
        rime::UserDictManager manager(&deployer);
        rime::UserDictList dictList;
        manager.GetUserDictList(&dictList);
        for (const auto& dictName : dictList) {
            jstring jName = new_utf8_string(env, dictName.c_str());
            env->CallBooleanMethod(listObj, addMethod, jName);
            env->DeleteLocalRef(jName);
        }
    } catch (const std::exception& e) {
        LOGE("getUserDictList failed: %s", e.what());
    } catch (...) {
        LOGE("getUserDictList failed with unknown exception");
    }
    return listObj;
}

template<typename F>
static bool with_system_dict_table_locked(const std::string& dict_name, F&& action) {
    if (dict_name.empty()) return false;
    try {
        rime::Deployer& deployer(rime::Service::instance().deployer());
        rime::path table_path = deployer.user_data_dir / "build" / (dict_name + ".table.bin");
        if (!std::filesystem::exists(table_path)) {
            table_path = deployer.shared_data_dir / "build" / (dict_name + ".table.bin");
        }
        if (std::filesystem::exists(table_path)) {
            rime::Table table(table_path);
            if (table.Load()) {
                action(table);
                return true;
            }
        }
    } catch (const std::exception& e) {
        LOGE("with_system_dict_table_locked failed for %s: %s", dict_name.c_str(), e.what());
    } catch (...) {
        LOGE("with_system_dict_table_locked failed for %s with unknown exception", dict_name.c_str());
    }
    return false;
}

static bool is_entry_in_table(
    rime::Table& table,
    const std::string& phrase,
    const rime::Code& code
) {
    if (phrase.empty() || code.empty()) return false;
    rime::TableAccessor a;
    if (code.size() == 1) {
        a = table.QueryWords(code[0]);
    } else {
        a = table.QueryPhrases(code);
    }
    while (!a.exhausted()) {
        if (a.code() == code) {
            const rime::table::Entry* e = a.entry();
            if (e && table.GetEntryText(*e) == phrase) {
                return true;
            }
        }
        a.Next();
    }
    return false;
}

static bool is_entry_in_system_dict_locked(
    const std::string& dict_name,
    const std::string& phrase,
    const rime::Code& code,
    bool& found
) {
    found = false;
    if (phrase.empty() || code.empty() || dict_name.empty()) return false;
    return with_system_dict_table_locked(dict_name, [&](rime::Table& table) {
        found = is_entry_in_table(table, phrase, code);
    });
}

static bool parse_code_to_syllables(
    const std::string& code_str,
    const std::unordered_map<std::string, rime::SyllableId>& syllable_map,
    rime::Code& out_code
) {
    out_code.clear();
    if (code_str.empty()) return false;
    size_t start = 0;
    while (start < code_str.size()) {
        while (start < code_str.size() && (code_str[start] == ' ' || code_str[start] == '\t')) {
            start++;
        }
        if (start >= code_str.size()) break;
        size_t end = start;
        while (end < code_str.size() && code_str[end] != ' ' && code_str[end] != '\t') {
            end++;
        }
        std::string syl = code_str.substr(start, end - start);
        auto it = syllable_map.find(syl);
        if (it == syllable_map.end()) {
            return false;
        }
        out_code.push_back(it->second);
        start = end;
    }
    return !out_code.empty();
}

// 20. checkWordsInSystemDict
static jbooleanArray JNICALL native_checkWordsInSystemDict(
        JNIEnv *env, jclass clazz, jstring dictName, jobjectArray phrases, jobjectArray codes) {
    if (!dictName || !phrases || !codes) return nullptr;
    const char* c_dict_name = env->GetStringUTFChars(dictName, nullptr);
    if (!c_dict_name) return nullptr;
    std::string dict_name_str(c_dict_name);
    env->ReleaseStringUTFChars(dictName, c_dict_name);

    jsize count = env->GetArrayLength(phrases);
    if (env->GetArrayLength(codes) != count) return nullptr;
    jbooleanArray resultArray = env->NewBooleanArray(count);
    if (!resultArray || count == 0) return resultArray;

    std::vector<jboolean> results(count, JNI_FALSE);

    std::lock_guard<std::recursive_mutex> lock(g_rime_mutex);
    if (!g_rime_api || !g_rime_initialized) {
        return nullptr;
    }

    bool query_ok = with_system_dict_table_locked(dict_name_str, [&](rime::Table& table) {
        std::unordered_map<std::string, rime::SyllableId> syllable_map;
        auto* meta = table.metadata();
        if (meta && meta->syllabary) {
            auto* syllabary = meta->syllabary.get();
            for (size_t i = 0; i < syllabary->size; ++i) {
                std::string s = table.GetSyllableById(static_cast<int>(i));
                if (!s.empty()) {
                    syllable_map[s] = static_cast<rime::SyllableId>(i);
                }
            }
        }

        rime::Code code;
        for (jsize i = 0; i < count; ++i) {
            jstring jPhrase = static_cast<jstring>(env->GetObjectArrayElement(phrases, i));
            jstring jCode = static_cast<jstring>(env->GetObjectArrayElement(codes, i));
            if (jPhrase && jCode) {
                std::string phrase;
                std::string code_str;
                const bool phrase_valid = read_utf8_string(env, jPhrase, phrase);
                const bool code_valid = read_utf8_string(env, jCode, code_str);
                if (!phrase_valid || !code_valid) {
                    env->DeleteLocalRef(jPhrase);
                    env->DeleteLocalRef(jCode);
                    return;
                }
                if (parse_code_to_syllables(code_str, syllable_map, code)) {
                    if (is_entry_in_table(table, phrase, code)) {
                        results[i] = JNI_TRUE;
                    }
                }
            }
            if (jPhrase) env->DeleteLocalRef(jPhrase);
            if (jCode) env->DeleteLocalRef(jCode);
        }
    });

    if (!query_ok || env->ExceptionCheck()) {
        return nullptr;
    }

    env->SetBooleanArrayRegion(resultArray, 0, count, results.data());
    return resultArray;
}

// 21. getCandidateDeletableType
// 0: DELETABLE_NONE (pure system word with no userdb record, commits <= 0)
// 1: DELETABLE_CUSTOM_WORD (user custom word not in system dict -> "删除自造词")
// 2: DELETABLE_USER_TUNED (system word with user frequency record -> "重置词频")
static jint JNICALL native_getCandidateDeletableType(
    JNIEnv *env,
    jclass clazz,
    jint target_page_no,
    jint target_page_index
) {
    std::lock_guard<std::recursive_mutex> lock(g_rime_mutex);
    if (!g_rime_api || target_page_no < 0 || target_page_index < 0) return 0;
    RimeSessionId sid = get_session_locked();
    if (!sid) return 0;

    auto session = rime::Service::instance().GetSession(sid);
    if (!session) return 0;
    auto* ctx = session->context();
    if (!ctx || !ctx->HasMenu() || ctx->composition().empty()) return 0;
    auto schema = session->schema();
    if (!schema) return 0;

    size_t page_size = schema->page_size() > 0 ? static_cast<size_t>(schema->page_size()) : 1;
    size_t global_index = static_cast<size_t>(target_page_no) * page_size + static_cast<size_t>(target_page_index);

    auto& seg = ctx->composition().back();
    auto cand = seg.GetCandidateAt(global_index);
    if (!cand) return 0;
    cand = rime::Candidate::GetGenuineCandidate(cand);
    if (!cand) return 0;

    const std::string& type = cand->type();
    // If not produced by userdb (e.g. static "phrase" or "table"), it has no user commits
    if (type != "user_phrase" && type != "user_table") {
        return 0;
    }

    std::string dict_name;
    auto* config = schema->config();
    if (config) {
        config->GetString("translator/dictionary", &dict_name);
    }
    if (dict_name.empty()) {
        dict_name = schema->schema_id();
    }

    const std::string& text = cand->text();
    auto phrase_cand = rime::As<rime::Phrase>(cand);
    if (!phrase_cand) {
        return 0;
    }
    bool in_system_dict = false;
    // yagni: table lookup failure treats userdb candidates as custom words; upgrade when per-schema table error telemetry exists
    if (!is_entry_in_system_dict_locked(dict_name, text, phrase_cand->code(), in_system_dict)) {
        return 1;
    }
    return in_system_dict ? 2 : 1;
}

// JNINativeMethod Table
static const JNINativeMethod g_methods[] = {
    {"startupRime",           "(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;Z)Z", (void*)native_startupRime},
    {"exitRime",              "()V",                                                              (void*)native_exitRime},
    {"setRimePageSize",       "(I)V",                                                             (void*)native_setRimePageSize},
    {"processRimeKeyAndSnapshot", "(II)Lorg/bitfennec/lime/core/RimeSnapshot;",                    (void*)native_processRimeKeyAndSnapshot},
    {"clearRimeCompositionAndSnapshot", "()Lorg/bitfennec/lime/core/RimeSnapshot;",                 (void*)native_clearRimeCompositionAndSnapshot},
    {"nativeSetUserDictWritesEnabled", "(Z)V",                                                    (void*)native_setUserDictWritesEnabled},
    {"setRimeOption",         "(Ljava/lang/String;Z)V",                                           (void*)native_setRimeOption},
    {"getRimeOption",         "(Ljava/lang/String;)Z",                                            (void*)native_getRimeOption},
    {"getCurrentRimeSchema",  "()Ljava/lang/String;",                                             (void*)native_getCurrentRimeSchema},
    {"selectRimeSchema",      "(Ljava/lang/String;)Z",                                            (void*)native_selectRimeSchema},
    {"selectRimeCandidateOnPageAndSnapshot", "(III)Lorg/bitfennec/lime/core/RimeSnapshot;",          (void*)native_selectRimeCandidateOnPageAndSnapshot},
    {"deleteRimeCandidateOnPageAndSnapshot", "(III)Lorg/bitfennec/lime/core/RimeSnapshot;",          (void*)native_deleteRimeCandidateOnPageAndSnapshot},
    {"moveRimeCandidatePageAndSnapshot", "(II)Lorg/bitfennec/lime/core/RimeSnapshot;",              (void*)native_moveRimeCandidatePageAndSnapshot},
    {"snapshotRimeState",     "()Lorg/bitfennec/lime/core/RimeSnapshot;",                         (void*)native_snapshotRimeState},
    {"selectT9PrefixAndSnapshot", "(Ljava/lang/String;)Lorg/bitfennec/lime/core/RimeSnapshot;", (void*)native_selectT9PrefixAndSnapshot},
    {"getRimeKeycodeByName",  "(Ljava/lang/String;)I",                                            (void*)native_getRimeKeycodeByName},
    {"prewarmOpencc",         "(Ljava/lang/String;)V",                                            (void*)native_prewarmOpencc},
    {"openccConvert",         "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",         (void*)native_openccConvert},
    {"exportUserDict",        "(Ljava/lang/String;Ljava/lang/String;)I",                          (void*)native_exportUserDict},
    {"importUserDict",        "(Ljava/lang/String;Ljava/lang/String;)I",                          (void*)native_importUserDict},
    {"getUserDictList",       "()Ljava/util/List;",                                               (void*)native_getUserDictList},
    {"checkWordsInSystemDict", "(Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;)[Z",     (void*)native_checkWordsInSystemDict},
    {"getCandidateDeletableType", "(II)I",                                                        (void*)native_getCandidateDeletableType},
};

static jclass find_global_class(JNIEnv* env, const std::string& class_name) {
    jclass local_class = env->FindClass(class_name.c_str());
    if (!local_class) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return nullptr;
    }
    jclass global_class = static_cast<jclass>(env->NewGlobalRef(local_class));
    if (!global_class && env->ExceptionCheck()) env->ExceptionClear();
    env->DeleteLocalRef(local_class);
    return global_class;
}

static jmethodID find_method(JNIEnv* env, jclass clazz, const char* name, const char* signature) {
    if (!clazz) return nullptr;
    jmethodID method = env->GetMethodID(clazz, name, signature);
    if (!method && env->ExceptionCheck()) env->ExceptionClear();
    return method;
}

static void init_cache(JNIEnv* env) {
    g_cache.compClass = find_global_class(env, g_package_prefix + "/RimeComposition");
    g_cache.compInit = find_method(env, g_cache.compClass, "<init>", "(IIIILjava/lang/String;Ljava/lang/String;)V");

    g_cache.pageCandidateClass = find_global_class(env, g_package_prefix + "/RimePageCandidate");
    g_cache.pageCandidateInit = find_method(env, g_cache.pageCandidateClass, "<init>", "(ILjava/lang/String;Ljava/lang/String;)V");

    g_cache.t9MetadataClass = find_global_class(env, g_package_prefix + "/RimeT9Metadata");
    g_cache.t9MetadataInit = find_method(env, g_cache.t9MetadataClass, "<init>", "(Ljava/lang/String;[Ljava/lang/String;I)V");

    g_cache.candidatePageClass = find_global_class(env, g_package_prefix + "/RimeCandidatePage");
    std::string pageCandidateArraySig = "[L" + g_package_prefix + "/RimePageCandidate;";
    g_cache.candidatePageInit = find_method(env, g_cache.candidatePageClass, "<init>",
        ("(IIZZI" + pageCandidateArraySig + ")V").c_str());

    g_cache.snapshotClass = find_global_class(env, g_package_prefix + "/RimeSnapshot");
    std::string snapshotSig = "(ZLjava/lang/String;Ljava/lang/String;L" + g_package_prefix +
        "/RimeComposition;Ljava/lang/String;L" + g_package_prefix + "/RimeCandidatePage;L" +
        g_package_prefix + "/RimeT9Metadata;)V";
    g_cache.snapshotInit = find_method(env, g_cache.snapshotClass, "<init>", snapshotSig.c_str());

    g_cache.stringClass = find_global_class(env, "java/lang/String");
}

static void clear_cache(JNIEnv* env) {
    jclass* classes[] = {
        &g_cache.compClass,
        &g_cache.pageCandidateClass,
        &g_cache.t9MetadataClass,
        &g_cache.candidatePageClass,
        &g_cache.snapshotClass,
        &g_cache.stringClass,
    };
    for (jclass* clazz : classes) {
        if (*clazz) {
            env->DeleteGlobalRef(*clazz);
            *clazz = nullptr;
        }
    }
    g_cache = RimeJniCache{};
}

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    rime_require_module_predict();

    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    g_package_prefix = "org/bitfennec/lime/core";
    init_cache(env);

    jclass rimeClass = env->FindClass("org/bitfennec/lime/core/Rime");
    if (rimeClass) {
        if (env->RegisterNatives(rimeClass, g_methods, sizeof(g_methods) / sizeof(JNINativeMethod)) < 0) {
            LOGE("Failed to register native methods for org.bitfennec.lime.core.Rime");
            env->DeleteLocalRef(rimeClass);
            return JNI_ERR;
        }
        LOGI("Successfully registered native methods for org.bitfennec.lime.core.Rime");
        env->DeleteLocalRef(rimeClass);
    } else {
        LOGE("Could not find class org/bitfennec/lime/core/Rime");
        return JNI_ERR;
    }

    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* vm, void* reserved) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) {
        clear_cache(env);
    }
}

}
