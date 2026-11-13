//
// Copyright RIME Developers
// Distributed under the BSD License
//
// 2012-01-17 GONG Chen <chen.sst@gmail.com>
//
#include <boost/algorithm/string.hpp>
#include <utf8.h>
#include <rime/algo/calculus.h>
#include <rime/common.h>

namespace rime {

const double kAbbreviationPenalty = -0.6931471805599453;
const double kFuzzySpellingPenalty = -0.6931471805599453;
const double kCorrectionPenalty = -4.605170185988091;

Calculus::Calculus() {
  Register("xlit", &Transliteration::Parse);
  Register("xform", &Transformation::Parse);
  Register("erase", &Erasion::Parse);
  Register("derive", &Derivation::Parse);
  Register("fuzz", &Fuzzing::Parse);
  Register("abbrev", &Abbreviation::Parse);
}

void Calculus::Register(const string& token, Calculation::Factory* factory) {
  factories_[token] = factory;
}

Calculation* Calculus::Parse(const string& definition) {
  size_t sep = definition.find_first_not_of("zyxwvutsrqponmlkjihgfedcba");
  if (sep == string::npos)
    return NULL;
  vector<string> args;
  boost::split(args, definition,
               boost::is_from_range(definition[sep], definition[sep]));
  if (args.empty())
    return NULL;
  auto it = factories_.find(args[0]);
  if (it == factories_.end())
    return NULL;
  Calculation* result = (*it->second)(args);
  return result;
}

Calculation* Transliteration::Parse(const vector<string>& args) {
  if (args.size() < 3)
    return NULL;
  const string& left(args[1]);
  const string& right(args[2]);
  const char* pl = left.c_str();
  const char* pr = right.c_str();
  uint32_t cl, cr;
  map<uint32_t, uint32_t> char_map;
  while ((cl = utf8::unchecked::next(pl)), (cr = utf8::unchecked::next(pr)),
         cl && cr) {
    char_map[cl] = cr;
  }
  if (cl == 0 && cr == 0) {
    the<Transliteration> x(new Transliteration);
    x->char_map_.swap(char_map);
    return x.release();
  }
  return NULL;
}

bool Transliteration::Apply(Spelling* spelling) {
  if (!spelling || spelling->str.empty())
    return false;
  bool modified = false;
  const char* p = spelling->str.c_str();
  const int buffer_len = 256;
  char buffer[buffer_len] = "";
  char* q = buffer;
  uint32_t c;
  while ((c = utf8::unchecked::next(p))) {
    if (q - buffer > buffer_len - 7) {
      modified = false;
      break;
    }
    if (char_map_.find(c) != char_map_.end()) {
      c = char_map_[c];
      modified = true;
    }
    q = utf8::unchecked::append(c, q);
  }
  if (modified) {
    *q = '\0';
    spelling->str.assign(buffer);
  }
  return modified;
}

Calculation* Transformation::Parse(const vector<string>& args) {
  if (args.size() < 3)
    return NULL;
  const string& left(args[1]);
  const string& right(args[2]);
  if (left.empty())
    return NULL;
  the<Transformation> x(new Transformation);
  x->pattern_.assign(left);
  x->replacement_.assign(right);
  return x.release();
}

bool Transformation::Apply(Spelling* spelling) {
  if (!spelling || spelling->str.empty())
    return false;
  string result = boost::regex_replace(spelling->str, pattern_, replacement_);
  if (result == spelling->str)
    return false;
  spelling->str.swap(result);
  return true;
}

Calculation* Erasion::Parse(const vector<string>& args) {
  if (args.size() < 2)
    return NULL;
  const string& pattern(args[1]);
  if (pattern.empty())
    return NULL;
  the<Erasion> x(new Erasion);
  x->pattern_.assign(pattern);
  return x.release();
}

bool Erasion::Apply(Spelling* spelling) {
  if (!spelling || spelling->str.empty())
    return false;
  if (!boost::regex_match(spelling->str, pattern_))
    return false;
  spelling->str.clear();
  return true;
}

Calculation* Derivation::Parse(const vector<string>& args) {
  if (args.size() < 3)
    return NULL;

  const string& left(args[1]);
  const string& right(args[2]);
  if (left.empty())
    return NULL;

  if (args.size() > 3) {
    const string& tag = args[3];

    if (tag == "correction") {
      the<Correction> x(new Correction);
      x->pattern_.assign(left);
      x->replacement_.assign(right);
      return x.release();
    }

    if (tag == "abbrev") {
      the<Abbreviation> x(new Abbreviation);
      x->pattern_.assign(left);
      x->replacement_.assign(right);
      return x.release();
    }

    if (tag == "fuzz") {
      the<Fuzzing> x(new Fuzzing);
      x->pattern_.assign(left);
      x->replacement_.assign(right);
      return x.release();
    }

  }

  the<Derivation> x(new Derivation);
  x->pattern_.assign(left);
  x->replacement_.assign(right);
  return x.release();
}

Calculation* Fuzzing::Parse(const vector<string>& args) {
  if (args.size() < 3)
    return NULL;
  const string& left(args[1]);
  const string& right(args[2]);
  if (left.empty())
    return NULL;
  the<Fuzzing> x(new Fuzzing);
  x->pattern_.assign(left);
  x->replacement_.assign(right);
  return x.release();
}

bool Fuzzing::Apply(Spelling* spelling) {
  bool result = Transformation::Apply(spelling);
  if (result) {
    spelling->properties.type = kFuzzySpelling;
    spelling->properties.credibility += kFuzzySpellingPenalty;
  }
  return result;
}

Calculation* Abbreviation::Parse(const vector<string>& args) {
  if (args.size() < 3)
    return NULL;
  const string& left(args[1]);
  const string& right(args[2]);
  if (left.empty())
    return NULL;
  the<Abbreviation> x(new Abbreviation);
  x->pattern_.assign(left);
  x->replacement_.assign(right);
  return x.release();
}

bool Abbreviation::Apply(Spelling* spelling) {
  bool result = Transformation::Apply(spelling);
  if (result) {
    spelling->properties.type = kAbbreviation;
    spelling->properties.credibility += kAbbreviationPenalty;
  }
  return result;
}

bool Correction::Apply(Spelling* spelling) {
  bool result = Transformation::Apply(spelling);
  if (result) {
    spelling->properties.is_correction = true;
    spelling->properties.credibility += kCorrectionPenalty;
  }
  return result;
}

bool Calculation::ApplyMapped(MappedPreedit* value) {
  if (!value || !value->valid())
    return false;
  Spelling spelling(value->text);
  const auto source = value->Source(0, value->text.size());
  if (!Apply(&spelling))
    return false;
  *value = MappedPreedit::Literal(spelling.str, source);
  return true;
}

bool Transliteration::ApplyMapped(MappedPreedit* value) {
  if (!value || !value->valid())
    return false;
  Spelling spelling(value->text);
  if (!Apply(&spelling))
    return false;
  MappedPreedit result;
  auto cursor = value->text.begin();
  while (cursor != value->text.end()) {
    const size_t start = cursor - value->text.begin();
    const auto codepoint = utf8::next(cursor, value->text.end());
    const size_t end = cursor - value->text.begin();
    const auto found = char_map_.find(codepoint);
    if (found == char_map_.end()) {
      result.Append(value->Slice(start, end));
    } else {
      string replacement;
      utf8::append(found->second, std::back_inserter(replacement));
      result.Append(MappedPreedit::Literal(replacement, value->Source(start, end)));
    }
  }
  if (result.text != spelling.str)
    result = MappedPreedit::Literal(spelling.str, value->Source(0, value->text.size()));
  *value = std::move(result);
  return true;
}

struct MappedReplacementPart {
  string literal;
  size_t start = 0;
  size_t end = 0;
  bool capture = false;
};

static bool mapped_replacement_parts(const boost::smatch& match,
                                     const string& replacement,
                                     const string& input,
                                     vector<MappedReplacementPart>* parts) {
  string literal;
  const auto flush = [&]() {
    if (!literal.empty()) {
      parts->push_back({std::move(literal), 0, 0, false});
      literal.clear();
    }
  };
  for (size_t i = 0; i < replacement.size(); ++i) {
    const char character = replacement[i];
    if (character == '\\')
      return false;
    if (character != '$') {
      literal += character;
      continue;
    }
    if (++i >= replacement.size())
      return false;
    if (replacement[i] == '$') {
      literal += '$';
      continue;
    }
    size_t group = 0;
    if (replacement[i] == '&') {
      group = 0;
    } else if (replacement[i] >= '0' && replacement[i] <= '9') {
      group = replacement[i] - '0';
      if (i + 1 < replacement.size() && replacement[i + 1] >= '0' &&
          replacement[i + 1] <= '9') {
        group = group * 10 + replacement[++i] - '0';
      }
    } else {
      return false;
    }
    flush();
    if (group < match.size() && match[group].matched) {
      parts->push_back({"", size_t(match[group].first - input.begin()),
                        size_t(match[group].second - input.begin()), true});
    }
  }
  flush();
  return true;
}

bool Transformation::ApplyMapped(MappedPreedit* value) {
  if (!value || value->text.empty() || !value->valid())
    return false;
  const string expected = boost::regex_replace(value->text, pattern_, replacement_);
  if (expected == value->text)
    return false;
  MappedPreedit result;
  size_t position = 0;
  for (boost::sregex_iterator iterator(value->text.begin(), value->text.end(), pattern_),
       end; iterator != end; ++iterator) {
    const auto& match = *iterator;
    const size_t start = match[0].first - value->text.begin();
    const size_t finish = match[0].second - value->text.begin();
    result.Append(value->Slice(position, start));
    vector<MappedReplacementPart> parts;
    MappedPreedit replacement;
    bool supported = mapped_replacement_parts(match, replacement_, value->text, &parts);
    size_t consumed = start;
    for (size_t index = 0; supported && index < parts.size(); ++index) {
      const auto& part = parts[index];
      if (part.capture) {
        if (part.start < consumed) {
          supported = false;
          break;
        }
        if (part.start > consumed)
          replacement.ExtendLast(value->Source(consumed, part.start));
        replacement.Append(value->Slice(part.start, part.end));
        consumed = part.end;
      } else {
        size_t next = finish;
        for (size_t following = index + 1; following < parts.size(); ++following) {
          if (parts[following].capture) {
            next = parts[following].start;
            break;
          }
        }
        if (next < consumed) {
          supported = false;
          break;
        }
        replacement.Append(MappedPreedit::Literal(part.literal,
                                                  value->Source(consumed, next)));
        consumed = next;
      }
    }
    const auto source = value->Source(start, finish);
    if (supported && !replacement.text.empty()) {
      replacement.ExtendFirst({source.start, source.start});
      replacement.ExtendLast({source.end, source.end});
    }
    const string formatted = match.format(replacement_);
    if (!supported || replacement.text != formatted)
      replacement = MappedPreedit::Literal(formatted, source);
    result.Append(replacement);
    position = finish;
  }
  result.Append(value->Slice(position, value->text.size()));
  if (result.text != expected)
    result = MappedPreedit::Literal(expected, value->Source(0, value->text.size()));
  *value = std::move(result);
  return true;
}

}
