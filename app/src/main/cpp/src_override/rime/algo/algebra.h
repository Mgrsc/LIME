//
// Copyright RIME Developers
// Distributed under the BSD License
//
// 2012-01-19 GONG Chen <chen.sst@gmail.com>
//

#ifndef RIME_ALGEBRA_H_
#define RIME_ALGEBRA_H_

#include <rime/common.h>
#include <rime/config.h>
#include "spelling.h"
#include <rime/algo/mapped_preedit.h>

namespace rime {

class Calculation;
class Schema;

class Script : public map<string, vector<Spelling>> {
 public:
  RIME_DLL bool AddSyllable(const string& syllable);
  void Merge(const string& s,
             const SpellingProperties& sp,
             const vector<Spelling>& v);
  void Dump(const path& file_path) const;
};

class Projection {
 public:
  RIME_DLL bool Load(an<ConfigList> settings);

  RIME_DLL bool Apply(string* value);
  RIME_DLL bool Apply(MappedPreedit* value);

  RIME_DLL bool Apply(Script* value);

 protected:
  vector<of<Calculation>> calculation_;
};

}

#endif
