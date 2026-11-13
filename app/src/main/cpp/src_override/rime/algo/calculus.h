//
// Copyright RIME Developers
// Distributed under the BSD License
//
// 2012-01-17 GONG Chen <chen.sst@gmail.com>
//

#ifndef RIME_CALCULUS_H_
#define RIME_CALCULUS_H_

#include <stdint.h>
#include <boost/regex.hpp>
#include <rime_api.h>
#include <rime/common.h>
#include "spelling.h"
#include <rime/algo/mapped_preedit.h>

namespace rime {

class Calculation {
 public:
  using Factory = Calculation*(const vector<string>& args);

  Calculation() = default;
  virtual ~Calculation() = default;
  virtual bool Apply(Spelling* spelling) = 0;
  virtual bool addition() { return true; }
  virtual bool deletion() { return true; }
  virtual bool ApplyMapped(MappedPreedit* value);
};

class Calculus {
 public:
  RIME_DLL Calculus();
  void Register(const string& token, Calculation::Factory* factory);
  RIME_DLL Calculation* Parse(const string& definition);

 private:
  map<string, Calculation::Factory*> factories_;
};

class Transliteration : public Calculation {
 public:
  static Factory Parse;
  bool Apply(Spelling* spelling) override;
  bool ApplyMapped(MappedPreedit* value) override;

 protected:
  map<uint32_t, uint32_t> char_map_;
};

class Transformation : public Calculation {
 public:
  static Factory Parse;
  bool Apply(Spelling* spelling) override;
  bool ApplyMapped(MappedPreedit* value) override;

 protected:
  boost::regex pattern_;
  string replacement_;
};

class Erasion : public Calculation {
 public:
  static Factory Parse;
  bool Apply(Spelling* spelling) override;
  bool addition() override { return false; }

 protected:
  boost::regex pattern_;
};

class Derivation : public Transformation {
 public:
  static Factory Parse;
  bool deletion() override { return false; }
};

class Fuzzing : public Derivation {
 public:
  static Factory Parse;
  bool Apply(Spelling* spelling) override;
};

class Abbreviation : public Derivation {
 public:
  static Factory Parse;
  bool Apply(Spelling* spelling) override;
};

class Correction : public Derivation {
 public:
  bool Apply(Spelling* spelling) override;
};

}

#endif
