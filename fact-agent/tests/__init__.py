"""
fact-agent 离线自动化测试包

设计约束（见 requirements-dev.txt）：
1. 只用 Python 标准库 unittest（虚拟环境未安装 pytest，且无法联网 pip install）；
2. 全程离线：不调用真实 LLM、不搜索、不抓网页；所有外部依赖用 tests/_helpers.py
   中的假对象注入；
3. 不读写生产 data/ 目录（FAISS 索引）；需要落盘时一律用 tempfile.mkdtemp()；
4. 不加载真实 bge 模型（慢且需要磁盘 IO），用确定性假 embedding 代替。

注意：测试目录下的模块名以 `test_` 开头（test_detect_service / test_dedup_service /
test_credibility_search），共享工具放在不匹配发现模式的 `_helpers.py` 中，避免被
unittest 误当作测试模块加载。

运行方式（在 fact-agent/ 目录下）：
    .venv\\Scripts\\python.exe -m unittest discover -s tests -t . -v
"""
