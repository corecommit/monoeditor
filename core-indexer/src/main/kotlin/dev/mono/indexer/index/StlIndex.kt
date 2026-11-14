package dev.mono.indexer.index

import dev.mono.indexer.symbols.SymKind
import dev.mono.indexer.symbols.Symbol

/**
 * Curated index of the C++ standard library.
 *
 * Signatures are simplified for autocomplete display. The index is grouped by
 * header so that completion can light up exactly the STL headers a file
 * actually includes (plus a few always-on essentials). Members of common
 * containers are indexed with their container name so they only surface when
 * the container is in scope via an include.
 */
object StlIndex {

    private val rows = ArrayList<Symbol>()

    private fun header(name: String) = name

    private fun fn(name: String, sig: String, h: String) =
        rows.add(Symbol(name, SymKind.FUNCTION, sig, container = "std", header = header(h)))

    private fun cls(name: String, sig: String, h: String) =
        rows.add(Symbol(name, SymKind.CLASS, sig, container = "std", header = header(h)))

    private fun member(name: String, sig: String, container: String, h: String) =
        rows.add(Symbol(name, SymKind.MEMBER, sig, container = container, header = header(h)))

    private fun kw(name: String, h: String) =
        rows.add(Symbol(name, SymKind.VARIABLE, "", container = "std", header = header(h)))

    init {
        // <iostream>
        cls("ostream", "basic_ostream<char>", "iostream")
        cls("istream", "basic_istream<char>", "iostream")
        kw("cout", "iostream"); kw("cin", "iostream"); kw("cerr", "iostream"); kw("clog", "iostream")
        fn("endl", "ostream& endl(ostream&)", "iostream")
        fn("flush", "ostream& flush(ostream&)", "iostream")
        fn("hex", "ios_base& hex(ios_base&)", "iostream")
        fn("dec", "ios_base& dec(ios_base&)", "iostream")
        fn("oct", "ios_base& oct(ios_base&)", "iostream")
        fn("boolalpha", "ios_base& boolalpha(ios_base&)", "iostream")
        fn("fixed", "ios_base& fixed(ios_base&)", "iostream")
        fn("scientific", "ios_base& scientific(ios_base&)", "iostream")

        // <iomanip>
        fn("setw", "setw(int n)", "iomanip")
        fn("setprecision", "setprecision(int n)", "iomanip")
        fn("setfill", "setfill(char c)", "iomanip")

        // <string>
        cls("string", "basic_string<char>", "string")
        cls("wstring", "basic_string<wchar_t>", "string")
        cls("u8string", "basic_string<char8_t>", "string")
        fn("to_string", "string to_string(T value)", "string")
        fn("stoi", "int stoi(const string&, size_t* = 0, int = 10)", "string")
        fn("stol", "long stol(const string&, size_t* = 0, int = 10)", "string")
        fn("stoll", "long long stoll(const string&)", "string")
        fn("stoul", "unsigned long stoul(const string&)", "string")
        fn("stoull", "unsigned long long stoull(const string&)", "string")
        fn("stof", "float stof(const string&)", "string")
        fn("stod", "double stod(const string&)", "string")
        fn("getline", "istream& getline(istream&, string&, char = '\\n')", "string")
        for (m in listOf(
            "size() const -> size_t", "length() const -> size_t", "empty() const -> bool",
            "clear() -> void", "push_back(char) -> void", "pop_back() -> void",
            "append(const string&) -> string&", "insert(size_t, const string&) -> string&",
            "erase(size_t = 0, size_t = npos) -> string&",
            "replace(size_t, size_t, const string&) -> string&",
            "substr(size_t = 0, size_t = npos) const -> string",
            "find(const string&, size_t = 0) const -> size_t",
            "rfind(const string&, size_t = npos) const -> size_t",
            "c_str() const -> const char*", "data() const -> const char*",
            "at(size_t) -> char&", "front() -> char&", "back() -> char&",
            "resize(size_t) -> void", "reserve(size_t) -> void",
            "capacity() const -> size_t", "compare(const string&) const -> int",
            "begin() -> iterator", "end() -> iterator"
        )) {
            val name = m.substringBefore('(')
            member(name, m, "string", "string")
        }
        kw("npos", "string")

        // <vector>
        cls("vector", "vector<T>", "vector")
        for (m in listOf(
            "push_back(const T&) -> void", "emplace_back(Args&&...) -> T&",
            "pop_back() -> void", "size() const -> size_t", "empty() const -> bool",
            "clear() -> void", "resize(size_t) -> void", "reserve(size_t) -> void",
            "capacity() const -> size_t", "shrink_to_fit() -> void",
            "at(size_t) -> T&", "front() -> T&", "back() -> T&",
            "begin() -> iterator", "end() -> iterator", "data() -> T*",
            "insert(const_iterator, const T&) -> iterator",
            "erase(const_iterator) -> iterator", "emplace(...) -> iterator"
        )) member(m.substringBefore('('), m, "vector", "vector")

        // <array>
        cls("array", "array<T, N>", "array")
        for (m in listOf(
            "size() const -> size_t", "empty() const -> bool", "fill(const T&) -> void",
            "at(size_t) -> T&", "front() -> T&", "back() -> T&",
            "begin() -> iterator", "end() -> iterator", "data() -> T*"
        )) member(m.substringBefore('('), m, "array", "array")

        // <map> / <unordered_map>
        cls("map", "map<Key, T>", "map")
        cls("multimap", "multimap<Key, T>", "map")
        cls("unordered_map", "unordered_map<Key, T>", "unordered_map")
        cls("unordered_multimap", "unordered_multimap<Key, T>", "unordered_map")
        for (c in listOf("map", "unordered_map")) {
            for (m in listOf(
                "insert(const value_type&) -> pair<iterator,bool>",
                "emplace(Args&&...) -> pair<iterator,bool>",
                "at(const Key&) -> T&", "count(const Key&) const -> size_t",
                "find(const Key&) -> iterator", "erase(const Key&) -> size_t",
                "clear() -> void", "size() const -> size_t", "empty() const -> bool",
                "contains(const Key&) const -> bool",
                "lower_bound(const Key&) -> iterator", "upper_bound(const Key&) -> iterator",
                "begin() -> iterator", "end() -> iterator"
            )) member(m.substringBefore('('), m, c, if (c == "map") "map" else "unordered_map")
        }

        // <set> / <unordered_set>
        cls("set", "set<Key>", "set")
        cls("multiset", "multiset<Key>", "set")
        cls("unordered_set", "unordered_set<Key>", "unordered_set")
        for (c in listOf("set", "unordered_set")) {
            for (m in listOf(
                "insert(const Key&) -> pair<iterator,bool>",
                "emplace(Args&&...) -> pair<iterator,bool>",
                "count(const Key&) const -> size_t", "find(const Key&) -> iterator",
                "erase(const Key&) -> size_t", "clear() -> void",
                "size() const -> size_t", "empty() const -> bool",
                "contains(const Key&) const -> bool", "begin() -> iterator", "end() -> iterator"
            )) member(m.substringBefore('('), m, c, if (c == "set") "set" else "unordered_set")
        }

        // <list> / <deque> / <queue> / <stack>
        cls("list", "list<T>", "list")
        cls("deque", "deque<T>", "deque")
        cls("queue", "queue<T>", "queue")
        cls("priority_queue", "priority_queue<T>", "queue")
        cls("stack", "stack<T>", "stack")
        for (m in listOf(
            "push_back(const T&) -> void", "push_front(const T&) -> void",
            "pop_back() -> void", "pop_front() -> void",
            "insert(const_iterator, const T&) -> iterator",
            "erase(const_iterator) -> iterator", "sort() -> void",
            "unique() -> void", "merge(list&) -> void", "reverse() -> void",
            "splice(const_iterator, list&) -> void",
            "size() const -> size_t", "empty() const -> bool", "clear() -> void"
        )) member(m.substringBefore('('), m, "list", "list")
        for (m in listOf(
            "push_back(const T&) -> void", "push_front(const T&) -> void",
            "pop_back() -> void", "pop_front() -> void", "at(size_t) -> T&",
            "size() const -> size_t", "empty() const -> bool", "clear() -> void"
        )) member(m.substringBefore('('), m, "deque", "deque")
        for (m in listOf(
            "push(const T&) -> void", "pop() -> void", "front() -> T&", "back() -> T&",
            "size() const -> size_t", "empty() const -> bool"
        )) {
            member(m.substringBefore('('), m, "queue", "queue")
            member(m.substringBefore('('), m, "deque", "deque")
        }
        for (m in listOf(
            "push(const T&) -> void", "pop() -> void", "top() -> T&",
            "size() const -> size_t", "empty() const -> bool"
        )) member(m.substringBefore('('), m, "stack", "stack")
        for (m in listOf(
            "push(const T&) -> void", "pop() -> void", "top() -> const T&",
            "size() const -> size_t", "empty() const -> bool"
        )) member(m.substringBefore('('), m, "priority_queue", "queue")

        // <utility>
        cls("pair", "pair<T1, T2>", "utility")
        fn("make_pair", "pair<T1,T2> make_pair(T1, T2)", "utility")
        fn("swap", "void swap(T& a, T& b)", "utility")
        fn("move", "T&& move(T&)", "utility")
        fn("forward", "T&& forward(T&)", "utility")
        fn("exchange", "T exchange(T& obj, T new_value)", "utility")

        // <tuple>
        cls("tuple", "tuple<Ts...>", "tuple")
        fn("make_tuple", "tuple<Ts...> make_tuple(Ts...)", "tuple")
        fn("get", "T& get<I>(tuple&)", "tuple")

        // <memory>
        cls("unique_ptr", "unique_ptr<T>", "memory")
        cls("shared_ptr", "shared_ptr<T>", "memory")
        cls("weak_ptr", "weak_ptr<T>", "memory")
        fn("make_unique", "unique_ptr<T> make_unique(Args&&...)", "memory")
        fn("make_shared", "shared_ptr<T> make_shared(Args&&...)", "memory")
        for (m in listOf(
            "get() -> T*", "get() const -> T*", "reset(T* = nullptr) -> void",
            "release() -> T*", "use_count() const -> long",
            "operator bool() const -> bool", "swap(shared_ptr&) -> void"
        )) {
            member(m.substringBefore('('), m, "unique_ptr", "memory")
            member(m.substringBefore('('), m, "shared_ptr", "memory")
        }

        // <functional>
        cls("function", "function<R(Args...)>", "functional")
        cls("less", "less<T>", "functional")
        cls("greater", "greater<T>", "functional")

        // <algorithm>
        for (m in listOf(
            "sort(RandomIt, RandomIt)", "stable_sort(RandomIt, RandomIt)",
            "reverse(RandomIt, RandomIt)", "find(It, It, const T&)",
            "find_if(It, It, Pred)", "count(It, It, const T&)",
            "count_if(It, It, Pred)", "fill(It, It, const T&)",
            "for_each(It, It, F)", "transform(It, It, It, Out, F)",
            "copy(It, It, Out)", "copy_if(It, It, Out, Pred)",
            "remove(It, It, const T&)", "remove_if(It, It, Pred)",
            "unique(RandomIt, RandomIt)", "lower_bound(ForwardIt, ForwardIt, const T&)",
            "upper_bound(ForwardIt, ForwardIt, const T&)",
            "binary_search(ForwardIt, ForwardIt, const T&)",
            "min(const T&, const T&)", "max(const T&, const T&)",
            "min_element(It, It)", "max_element(It, It)",
            "clamp(const T&, const T&, const T&)",
            "all_of(It, It, Pred)", "any_of(It, It, Pred)", "none_of(It, It, Pred)",
            "generate(It, It, G)", "rotate(ForwardIt, ForwardIt, ForwardIt)",
            "partition(ForwardIt, ForwardIt, Pred)", "is_sorted(It, It)",
            "next_permutation(RandomIt, RandomIt)",
            "prev_permutation(RandomIt, RandomIt)"
        )) fn(m.substringBefore('('), m, "algorithm")

        // <numeric>
        fn("accumulate", "T accumulate(It, It, T init)", "numeric")
        fn("iota", "void iota(It, It, T value)", "numeric")
        fn("inner_product", "T inner_product(It, It, It, T)", "numeric")

        // <iterator>
        fn("distance", "size_t distance(It, It)", "iterator")
        fn("advance", "void advance(It&, n)", "iterator")
        fn("next", "It next(It, n = 1)", "iterator")
        fn("prev", "It prev(It, n = 1)", "iterator")
        fn("back_inserter", "back_insert_iterator<C> back_inserter(C&)", "iterator")
        fn("inserter", "insert_iterator<C> inserter(C&, It)", "iterator")

        // <cmath>
        for (m in listOf(
            "sqrt(double)", "pow(double, double)", "abs(double)", "fabs(double)",
            "floor(double)", "ceil(double)", "round(double)", "trunc(double)",
            "sin(double)", "cos(double)", "tan(double)", "asin(double)",
            "acos(double)", "atan(double)", "atan2(double, double)",
            "exp(double)", "log(double)", "log2(double)", "log10(double)",
            "fmod(double, double)", "hypot(double, double)",
            "isnan(double)", "isinf(double)", "fmin(double, double)", "fmax(double, double)"
        )) fn(m.substringBefore('('), m, "cmath")

        // <cstdio>
        for (m in listOf(
            "printf(const char*, ...)", "fprintf(FILE*, const char*, ...)",
            "snprintf(char*, size_t, const char*, ...)",
            "sprintf(char*, const char*, ...)",
            "scanf(const char*, ...)", "sscanf(const char*, const char*, ...)",
            "puts(const char*)", "putchar(int)", "getchar()",
            "fgets(char*, int, FILE*)", "fputs(const char*, FILE*)",
            "fopen(const char*, const char*)", "fclose(FILE*)",
            "fread(void*, size_t, size_t, FILE*)",
            "fwrite(const void*, size_t, size_t, FILE*)",
            "fseek(FILE*, long, int)", "ftell(FILE*)", "remove(const char*)",
            "rename(const char*, const char*)"
        )) fn(m.substringBefore('('), m, "cstdio")

        // <cstdlib>
        for (m in listOf(
            "malloc(size_t)", "calloc(size_t, size_t)", "realloc(void*, size_t)",
            "free(void*)", "abs(int)", "atoi(const char*)", "atof(const char*)",
            "strtol(const char*, char**, int)", "exit(int)", "abort()",
            "rand()", "srand(unsigned)", "system(const char*)",
            "qsort(void*, size_t, size_t, cmp)"
        )) fn(m.substringBefore('('), m, "cstdlib")

        // <cstring>
        for (m in listOf(
            "strlen(const char*)", "strcpy(char*, const char*)",
            "strncpy(char*, const char*, size_t)",
            "strcat(char*, const char*)", "strncat(char*, const char*, size_t)",
            "strcmp(const char*, const char*)",
            "strncmp(const char*, const char*, size_t)",
            "strchr(const char*, int)", "strrchr(const char*, int)",
            "strstr(const char*, const char*)", "strtok(char*, const char*)",
            "memcpy(void*, const void*, size_t)", "memmove(void*, const void*, size_t)",
            "memset(void*, int, size_t)", "memcmp(const void*, const void*, size_t)"
        )) fn(m.substringBefore('('), m, "cstring")

        // <cctype>
        for (m in listOf(
            "isalpha(int)", "isdigit(int)", "isalnum(int)", "isspace(int)",
            "isupper(int)", "islower(int)", "ispunct(int)", "toupper(int)", "tolower(int)"
        )) fn(m.substringBefore('('), m, "cctype")

        // <cassert>
        rows.add(Symbol("assert", SymKind.MACRO, "assert(expr)", header = "cassert"))

        // <ctime>
        for (m in listOf(
            "time(time_t*)", "clock()", "difftime(time_t, time_t)",
            "mktime(tm*)", "localtime(const time_t*)", "gmtime(const time_t*)",
            "strftime(char*, size_t, const char*, const tm*)"
        )) fn(m.substringBefore('('), m, "ctime")

        // <chrono>
        cls("duration", "duration<Rep, Period>", "chrono")
        cls("time_point", "time_point<Clock>", "chrono")
        cls("steady_clock", "steady_clock", "chrono")
        cls("system_clock", "system_clock", "chrono")
        cls("high_resolution_clock", "high_resolution_clock", "chrono")
        for (n in listOf("nanoseconds", "microseconds", "milliseconds", "seconds", "minutes", "hours"))
            cls(n, "chrono::$n", "chrono")

        // <limits>
        cls("numeric_limits", "numeric_limits<T>", "limits")
        member("min", "static T min()", "numeric_limits", "limits")
        member("max", "static T max()", "numeric_limits", "limits")

        // <bitset>
        cls("bitset", "bitset<N>", "bitset")
        for (m in listOf(
            "set(size_t) -> bitset&", "reset(size_t) -> bitset&",
            "flip(size_t) -> bitset&", "test(size_t) const -> bool",
            "count() const -> size_t", "size() const -> size_t",
            "to_ulong() const -> unsigned long", "to_string() const -> string"
        )) member(m.substringBefore('('), m, "bitset", "bitset")

        // <sstream>
        cls("stringstream", "basic_stringstream<char>", "sstream")
        cls("istringstream", "basic_istringstream<char>", "sstream")
        cls("ostringstream", "basic_ostringstream<char>", "sstream")
        member("str", "string str() const", "stringstream", "sstream")

        // <fstream>
        cls("ifstream", "basic_ifstream<char>", "fstream")
        cls("ofstream", "basic_ofstream<char>", "fstream")
        cls("fstream", "basic_fstream<char>", "fstream")
        for (m in listOf(
            "open(const char*, ios_base::openmode) -> void",
            "close() -> void", "is_open() const -> bool",
            "getline(char*, streamsize) -> istream&"
        )) member(m.substringBefore('('), m, "ifstream", "fstream")

        // <stdexcept>
        cls("exception", "exception", "stdexcept")
        cls("runtime_error", "runtime_error", "stdexcept")
        cls("logic_error", "logic_error", "stdexcept")
        cls("out_of_range", "out_of_range", "stdexcept")
        cls("invalid_argument", "invalid_argument", "stdexcept")
        cls("length_error", "length_error", "stdexcept")
        cls("bad_alloc", "bad_alloc", "stdexcept")
        member("what", "const char* what() const", "exception", "stdexcept")

        // <cstdint> — fixed width integer aliases
        for (n in listOf(
            "int8_t", "int16_t", "int32_t", "int64_t",
            "uint8_t", "uint16_t", "uint32_t", "uint64_t",
            "intmax_t", "uintmax_t", "intptr_t", "uintptr_t"
        )) rows.add(Symbol(n, SymKind.TYPEDEF, "typedef ... $n", header = "cstdint"))

        // <thread>
        cls("thread", "thread", "thread")
        for (m in listOf(
            "join() -> void", "detach() -> void", "joinable() const -> bool",
            "get_id() -> thread::id", "native_handle() -> native_handle_type",
            "hardware_concurrency() static -> unsigned int"
        )) member(m.substringBefore('('), m, "thread", "thread")
        fn("sleep_for", "void sleep_for(const duration&)", "thread")
        fn("sleep_until", "void sleep_until(const time_point&)", "thread")
        fn("yield", "void yield()", "thread")
        fn("get_id", "thread::id get_id()", "thread")

        // <mutex>
        for (n in listOf("mutex", "recursive_mutex", "timed_mutex", "recursive_timed_mutex"))
            cls(n, n, "mutex")
        for (m in listOf("lock() -> void", "unlock() -> void", "try_lock() -> bool"))
            member(m.substringBefore('('), m, "mutex", "mutex")
        cls("lock_guard", "lock_guard<Mutex>", "mutex")
        cls("scoped_lock", "scoped_lock<Mutexes...>", "mutex")
        cls("unique_lock", "unique_lock<Mutex>", "mutex")
        cls("once_flag", "once_flag", "mutex")
        fn("lock", "void lock(Mutex&...)", "mutex")
        fn("call_once", "void call_once(once_flag&, F&&)", "mutex")

        // <atomic>
        cls("atomic", "atomic<T>", "atomic")
        cls("atomic_flag", "atomic_flag", "atomic")
        for (m in listOf(
            "load(memory_order) const -> T",
            "store(T, memory_order) -> void",
            "exchange(T, memory_order) -> T",
            "fetch_add(T) -> T", "fetch_sub(T) -> T", "fetch_and(T) -> T",
            "fetch_or(T) -> T", "fetch_xor(T) -> T",
            "compare_exchange_weak(T&, T) -> bool",
            "compare_exchange_strong(T&, T) -> bool",
            "test_and_set() -> bool", "clear() -> void"
        )) member(m.substringBefore('('), m, "atomic", "atomic")
        fn("atomic_thread_fence", "void atomic_thread_fence(memory_order)", "atomic")
        fn("atomic_signal_fence", "void atomic_signal_fence(memory_order)", "atomic")

        // <random>
        cls("random_device", "random_device", "random")
        cls("mt19937", "mt19937", "random")
        cls("mt19937_64", "mt19937_64", "random")
        for (c in listOf("mt19937", "mt19937_64")) {
            for (m in listOf(
                "min() -> result_type", "max() -> result_type",
                "seed(result_type) -> void", "discard(unsigned long long) -> void"
            )) member(m.substringBefore('('), m, c, "random")
        }
        member("entropy", "double entropy() const", "random_device", "random")
        for (d in listOf(
            "uniform_int_distribution", "uniform_real_distribution", "normal_distribution"
        )) {
            cls(d, "$d<a, b>", "random")
            for (m in listOf(
                "min() const -> result_type", "max() const -> result_type",
                "a() const -> result_type", "b() const -> result_type", "reset() -> void"
            )) member(m.substringBefore('('), m, d, "random")
        }

        // <optional>
        cls("optional", "optional<T>", "optional")
        for (m in listOf(
            "has_value() const -> bool", "value() -> T&", "value() const -> const T&",
            "value_or(U&&) const -> T", "reset() -> void", "emplace(Args&&...) -> T&",
            "operator bool() const -> bool", "operator*() -> T&", "operator->() -> T*"
        )) member(m.substringBefore('('), m, "optional", "optional")
        kw("nullopt", "optional")
        fn("make_optional", "optional<T> make_optional(Args&&...)", "optional")

        // <variant>
        cls("variant", "variant<Ts...>", "variant")
        cls("monostate", "monostate", "variant")
        for (m in listOf("index() const -> size_t", "valueless_by_exception() const -> bool"))
            member(m.substringBefore('('), m, "variant", "variant")
        fn("visit", "R visit(Visitor&&, Variants&&...)", "variant")
        fn("holds_alternative", "bool holds_alternative<T>(const variant&)", "variant")
        fn("get", "T& get<T>(variant&)", "variant")
        fn("get_if", "T* get_if<T>(variant*)", "variant")

        // <string_view>
        cls("string_view", "basic_string_view<char>", "string_view")
        for (m in listOf(
            "size() const -> size_t", "length() const -> size_t", "empty() const -> bool",
            "data() const -> const char*", "front() const -> char", "back() const -> char",
            "remove_prefix(size_t) -> void", "remove_suffix(size_t) -> void",
            "substr(size_t = 0, size_t = npos) const -> string_view",
            "find(string_view, size_t = 0) const -> size_t",
            "rfind(string_view, size_t = npos) const -> size_t",
            "starts_with(string_view) const -> bool",
            "ends_with(string_view) const -> bool",
            "compare(string_view) const -> int",
            "begin() const -> const_iterator", "end() const -> const_iterator"
        )) member(m.substringBefore('('), m, "string_view", "string_view")

        // <regex>
        cls("regex", "basic_regex<char>", "regex")
        cls("wregex", "basic_regex<wchar_t>", "regex")
        cls("smatch", "match_results<string::const_iterator>", "regex")
        cls("cmatch", "match_results<const char*>", "regex")
        cls("sregex_iterator", "sregex_iterator", "regex")
        for (m in listOf(
            "str() const -> string", "length() const -> size_t",
            "position() const -> difference_type", "ready() const -> bool"
        )) member(m.substringBefore('('), m, "smatch", "regex")
        fn("regex_match", "bool regex_match(BidirIt, BidirIt, smatch&, const regex&, flags)", "regex")
        fn("regex_search", "bool regex_search(BidirIt, BidirIt, smatch&, const regex&, flags)", "regex")
        fn("regex_replace", "string regex_replace(string, const regex&, string, flags)", "regex")

        // <unistd.h> — POSIX
        for (m in listOf(
            "read(int, void*, size_t)", "write(int, const void*, size_t)",
            "open(const char*, int, ...)", "close(int)", "lseek(int, off_t, int)",
            "sleep(unsigned int)", "usleep(useconds_t)", "getpid()", "getppid()",
            "fork()", "isatty(int)", "access(const char*, int)", "unlink(const char*)",
            "rmdir(const char*)", "chdir(const char*)", "getcwd(char*, size_t)",
            "pipe(int[2])", "dup(int)", "dup2(int, int)", "ftruncate(int, off_t)"
        )) fn(m.substringBefore('('), m, "unistd.h")
    }

    /** All curated symbols. */
    val symbols: List<Symbol> = rows.toList()

    /** Header names covered by the curated index. */
    val headers: Set<String> = rows.mapTo(HashSet()) { it.header }

    private val byHeader: Map<String, List<Symbol>> = rows.groupBy { it.header }

    /** Symbols provided by the given header name (e.g. "vector", "cstdio"). */
    fun forHeader(header: String): List<Symbol> = byHeader[header.removePrefix("<").removeSuffix(">")] ?: emptyList()
}
