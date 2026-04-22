from fray_benchmark.bm_configs.jacontebe import JaConTeBe

bm = JaConTeBe()
for tc in bm.get_test_cases("java"):
    cp = ":".join(tc.executor.classpaths)
    args = " ".join(tc.executor.args)
    print(f"CLASS={tc.executor.clazz}")
    print(f"CP={cp}")
    print(f"ARGS={args}")
    print()
