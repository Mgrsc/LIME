#include "lua.h"
#include "lua_templates.h"

namespace LuaImpl {
  int wrap_common(lua_State *L, int (*cfunc)(lua_State *)) {
    char room[sizeof(C_State)];
    C_State *C = new (&room) C_State();
    lua_pushcfunction(L, cfunc);
    lua_insert(L, 1);
    lua_pushlightuserdata(L, (void *) C);
    lua_insert(L, 2);
    int status = lua_pcall(L, lua_gettop(L) - 1, LUA_MULTRET, 0);
    if (status != LUA_OK) {
      C->~C_State();
      lua_error(L);
      abort(); // unreachable
    }
    C->~C_State();
    return lua_gettop(L);
  }

  static int index(lua_State *L) {
    if (luaL_getmetafield(L, 1, "methods") != LUA_TNIL) {
      lua_pushvalue(L, 2);
      lua_rawget(L, -2);
      if (!lua_isnil(L, -1)) {
        return 1;
      } else {
        lua_pop(L, 1);
      }
    }

    if (luaL_getmetafield(L, 1, "vars_get") != LUA_TNIL) {
      lua_pushvalue(L, 2);
      lua_rawget(L, -2);
      if (!lua_isnil(L, -1)) {
        auto f = lua_tocfunction(L, -1);
        lua_pop(L, 1);
        if (f) {
          lua_remove(L, 2);
          return f(L);
        }
      }
    }
    return 0;
  }

  static int newindex(lua_State *L) {
    if (luaL_getmetafield(L, 1, "vars_set") != LUA_TNIL) {
      lua_pushvalue(L, 2);
      lua_rawget(L, -2);
      if (!lua_isnil(L, -1)) {
        auto f = lua_tocfunction(L, -1);
        lua_pop(L, 1);
        if (f) {
          lua_remove(L, 2);
          return f(L);
        }
      }
    }
    return 0;
  }

  namespace SetReg {
    static int raw_make(lua_State *L) {
      if (lua_gettop(L) != 1 || lua_type(L, 1) != LUA_TTABLE)
        return 0;
      int n = lua_rawlen(L, 1);
      lua_createtable(L, n, 0);
      for (int i = 1; i <= n; i++) {
        lua_rawgeti(L, 1, i);
        LuaType<bool>::pushdata(L, true);
        lua_rawset(L, -3);
      }
      luaL_setmetatable(L, "__set");
      return 1;
    }

    static int raw_union(lua_State *L) {
      int n = lua_gettop(L);
      for (int i = 1; i <= n; i++)
        if (lua_type(L, i) != LUA_TTABLE)
          return 0;
      lua_createtable(L, 0, 0);
      for (int i = 1; i <= n; i++) {
        lua_pushnil(L);
        while (lua_next(L, i) != 0) {
          lua_pushvalue(L, -2);
          LuaType<bool>::pushdata(L, true);
          lua_rawset(L, -5);
          lua_pop(L, 1);
        }
      }
      luaL_setmetatable(L, "__set");
      return 1;
    }

    static int raw_inter(lua_State *L) {
      int n = lua_gettop(L);
      for (int i = 1; i <= n; i++)
        if (lua_type(L, i) != LUA_TTABLE)
          return 0;
      lua_createtable(L, 0, 0);
      if (n > 0) {
        lua_pushnil(L);
        while (lua_next(L, 1) != 0) {
          bool b = true;
          for (int i = 2; b && i <= n; i++) {
            lua_pushvalue(L, -2);
            lua_rawget(L, i);
            b = (lua_type(L, -1) != LUA_TNIL);
            lua_pop(L, 1);
          }
          if (b) {
            lua_pushvalue(L, -2);
            LuaType<bool>::pushdata(L, true);
            lua_rawset(L, -5);
          }
          lua_pop(L, 1);
        }
      }
      luaL_setmetatable(L, "__set");
      return 1;
    }

    static int raw_diff(lua_State *L) {
      int n = lua_gettop(L);
      for (int i = 1; i <= n; i++)
        if (lua_type(L, i) != LUA_TTABLE)
          return 0;
      lua_createtable(L, 0, 0);
      if (n > 0) {
        lua_pushnil(L);
        while (lua_next(L, 1) != 0) {
          bool b = true;
          for (int i = 2; b && i <= n; i++) {
            lua_pushvalue(L, -2);
            lua_rawget(L, i);
            b = (lua_type(L, -1) == LUA_TNIL);
            lua_pop(L, 1);
          }
          if (b) {
            lua_pushvalue(L, -2);
            LuaType<bool>::pushdata(L, true);
            lua_rawset(L, -5);
          }
          lua_pop(L, 1);
        }
      }
      luaL_setmetatable(L, "__set");
      return 1;
    }

    static int raw_empty(lua_State *L) {
      if (lua_gettop(L) != 1)
        return 0;

      lua_pushnil(L);
      if (lua_next(L, 1) == 0) {
        LuaType<bool>::pushdata(L, true);
      } else {
        lua_pop(L, 2);
        LuaType<bool>::pushdata(L, false);
      }
      return 1;
    }

    static const luaL_Reg methods[] = {
      { "empty", raw_empty },
      { NULL, NULL },
    };

    static const luaL_Reg mt[] = {
      { "__index", index },
      { "__add", raw_union },
      { "__sub", raw_diff },
      { "__mul", raw_inter },
      { NULL, NULL },
    };
  }

  void export_set(lua_State *L) {
    lua_register(L, "Set", SetReg::raw_make);

    luaL_newmetatable(L, "__set");
    luaL_setfuncs(L, SetReg::mt, 0);
    lua_createtable(L, 0, 0);
    luaL_setfuncs(L, SetReg::methods, 0);
    lua_setfield(L, -2, "methods");
    lua_pop(L, 1);
  }

  static int yield(lua_State *L) {
    return lua_yield(L, lua_gettop(L));
  }

  static const char makeclosurekey = 'k';
  static const char luakey = 'k';

  static int pmain(lua_State *L) {
    // Defense-in-depth: whitelist safe Lua libraries only.
    // Invariant note: Lua 5.4's luaL_requiref writes only to package.loaded, never to _PRELOAD.
    // Because luaL_openlibs is never invoked, package.preload remains empty, making luaopen_*
    // C generators unreachable from Lua scripts. Setting restricted fields to nil is therefore
    // permanent and cannot be bypassed by resetting package.loaded["..."] and calling require().
    //
    // Note: The native sandbox restricts process-level execution, dlopen, and general OS operations.
    // Application-level import validation (UserDataManager.isSafeRimeFile) serves as the primary
    // perimeter defense against importing unauthorized scripts into the user directory.
    luaL_requiref(L, LUA_GNAME, luaopen_base, 1);
    lua_pop(L, 1);
    luaL_requiref(L, LUA_COLIBNAME, luaopen_coroutine, 1);
    lua_pop(L, 1);
    luaL_requiref(L, LUA_TABLIBNAME, luaopen_table, 1);
    lua_pop(L, 1);
    luaL_requiref(L, LUA_STRLIBNAME, luaopen_string, 1);
    lua_pop(L, 1);
    luaL_requiref(L, LUA_MATHLIBNAME, luaopen_math, 1);
    lua_pop(L, 1);
    luaL_requiref(L, LUA_UTF8LIBNAME, luaopen_utf8, 1);
    lua_pop(L, 1);

    // Provide safe os time/date functions only (required for schema date/time converters),
    // strictly eliminating execute, exit, getenv, remove, rename, tmpname, and setlocale.
    luaL_requiref(L, LUA_OSLIBNAME, luaopen_os, 1);
    const char *dangerous_os[] = {"execute", "exit", "getenv", "remove", "rename", "tmpname", "setlocale", nullptr};
    for (int i = 0; dangerous_os[i]; ++i) {
        lua_pushnil(L);
        lua_setfield(L, -2, dangerous_os[i]);
    }
    lua_pop(L, 1);

    // Sanitize package library:
    luaL_requiref(L, LUA_LOADLIBNAME, luaopen_package, 1);
    // (a) Remove loadlib
    lua_pushnil(L);
    lua_setfield(L, -2, "loadlib");
    // (b) Clear cpath
    lua_pushstring(L, "");
    lua_setfield(L, -2, "cpath");
    // (c) Crucial: Strip searcher_C (index 3) and searcher_Croot (index 4) from package.searchers
    // to strictly prevent dlopen of native libraries via require(), even if cpath is modified by script.
    lua_getfield(L, -1, "searchers");
    if (lua_istable(L, -1)) {
        lua_pushnil(L);
        lua_rawseti(L, -2, 3);
        lua_pushnil(L);
        lua_rawseti(L, -2, 4);
    }
    lua_pop(L, 1);
    lua_pop(L, 1);

    xluaopen_utf8(L);
    lua_register(L, "yield", yield);
    export_set(L);

    lua_pushlightuserdata(L, (void *)&makeclosurekey);
    luaL_dostring(L, "table.unpack = table.unpack or unpack\n"
                  "return function (f, ...)\n"
                  "local args = {...}\n"
                  "return (function () return f(table.unpack(args)) end)\n"
                  "end\n");
    lua_settable(L, LUA_REGISTRYINDEX);

    return 0;
  }
}

Lua::Lua() {
  L_ = luaL_newstate();
  if (L_) {
    lua_pushlightuserdata(L_, (void *)&LuaImpl::luakey);
    lua_pushlightuserdata(L_, (void *)this);
    lua_settable(L_, LUA_REGISTRYINDEX);

    lua_pushcfunction(L_, &LuaImpl::pmain);
    lua_call(L_, 0, 0);
  }
}

Lua::~Lua() {
  lua_close(L_);
}

Lua *Lua::from_state(lua_State *L) {
  Lua *lua;
  lua_pushlightuserdata(L, (void *)&LuaImpl::luakey);
  lua_gettable(L, LUA_REGISTRYINDEX);
  lua = (Lua *) lua_touserdata(L, -1);
  lua_pop(L, 1);
  return lua;
}

void Lua::to_state(std::function<void (lua_State *)> f) {
  f(L_);
}

std::shared_ptr<LuaObj> Lua::newthreadx(lua_State *L, int nargs) {
  lua_State *C = lua_newthread(L_);
  auto o = LuaObj::todata(L_, -1);
  lua_pop(L_, 1);

  lua_pushlightuserdata(C, (void *)&LuaImpl::makeclosurekey);
  lua_gettable(C, LUA_REGISTRYINDEX);
  lua_xmove(L, C, nargs);
  lua_call(C, nargs, 1);

  return o;
}

std::shared_ptr<LuaObj> Lua::getglobal(const std::string &v) {
  lua_getglobal(L_, v.c_str());
  auto o = LuaObj::todata(L_, -1);
  lua_pop(L_, 1);
  return o;
}

void Lua::gc() {
  lua_gc(L_, LUA_GCCOLLECT, 0);
}

void Lua::gc_step(int kb) {
  lua_gc(L_, LUA_GCSTEP, kb);
}

LuaObj::LuaObj(lua_State *L, int i) : L_(L) {
  lua_pushvalue(L, i);
  id_ = luaL_ref(L, LUA_REGISTRYINDEX);
}

LuaObj::~LuaObj() {
  luaL_unref(L_, LUA_REGISTRYINDEX, id_);
}

void LuaObj::pushdata(lua_State *L, std::shared_ptr<LuaObj> &o) {
  lua_rawgeti(L, LUA_REGISTRYINDEX, o->id_);
}

std::shared_ptr<LuaObj> LuaObj::todata(lua_State *L, int i) {
  return std::shared_ptr<LuaObj>(new LuaObj(L, i));
}

void lua_export_type(lua_State *L,
                     const LuaTypeInfo *type, lua_CFunction gc,
                     const luaL_Reg *funcs, const luaL_Reg *methods,
                     const luaL_Reg *vars_get, const luaL_Reg *vars_set) {
  for (int i = 0; funcs[i].name; i++) {
    lua_register(L, funcs[i].name, funcs[i].func);
  }

  luaL_newmetatable(L, type->name());
  lua_pushlightuserdata(L, (void *) type);
  lua_setfield(L, -2, "type");
  if (gc) {
    lua_pushcfunction(L, gc);
    lua_setfield(L, -2, "__gc");
  }
  lua_createtable(L, 0, 0);
  luaL_setfuncs(L, methods, 0);
  lua_setfield(L, -2, "methods");
  lua_createtable(L, 0, 0);
  luaL_setfuncs(L, vars_get, 0);
  lua_setfield(L, -2, "vars_get");
  lua_createtable(L, 0, 0);
  luaL_setfuncs(L, vars_set, 0);
  lua_setfield(L, -2, "vars_set");
  lua_pushcfunction(L, LuaImpl::index);
  lua_setfield(L, -2, "__index");
  lua_pushcfunction(L, LuaImpl::newindex);
  lua_setfield(L, -2, "__newindex");
  lua_pop(L, 1);
}
