## 1. Task 缁撴灉瀛楁 + 浠撳簱寰€杩旓紙task-execution锛?
- [x] 1.1 娴嬭瘯鍏堣锛歚TaskTest` 鏂█ `withResult`/`withLastRunAt` 鎷疯礉涓斾笉鏀瑰師瀵硅薄銆乣withStatus` 淇濈暀 result/lastRunAt锛沗FileSystemTaskRepositoryTest` 鏂█ result锛堝琛岋級+ lastRunAt 寰€杩斾竴鑷淬€佹棫鏂囦欢锛堟棤 Result/LastRun 琛岋級璇讳负 null 涓?schedule/鎻忚堪寰€杩斾笉鍙楀奖鍝?- [x] 1.2 `Task` record 澧?`String result` + `Instant lastRunAt`锛堣鑼?10 鍙傛瀯閫狅級锛涗繚鐣?8 鍙傚吋瀹规瀯閫狅紙result/lastRunAt=null锛夛紱`withStatus`/`withSchedule` 淇濈暀鏂板瓧娈碉紱澧?`withResult`/`withLastRunAt`
- [x] 1.3 `FileSystemTaskRepository`锛歚toMarkdown` 鍦?Created 涓?Updated 涔嬮棿鍐?`LastRun`/`Result` 琛岋紙Result 鍗曡杞箟銆佹寜 MAX_RESULT_CHARS 鎴柇锛夛紱`parseMarkdown` 璇诲洖锛坄extractField` + 鍙嶈浆涔?+ `parseNullableInstant`锛夛紱`extractDescription` 涓嶆敼
- [x] 1.4 `TaskManager.recordRun(id, result)` 瀹归敊钀?result+lastRunAt=now锛堜换鍔′笉瀛樺湪鍒?no-op锛夛紱琛ユ祴
- [x] 1.5 `mvn -pl pig-agent-task -am test` 缁匡紱鍕鹃€夋湰缁?
## 2. AgentRunner ad-hoc mandate 鍏ュ彛锛坱ask-execution锛?
- [x] 2.1 娴嬭瘯鍏堣锛歚AgentRunnerTest` 鈥斺€?`runMandate` 鐢?FakeModel(SUCCESS) 杩斿洖 SUCCESS+body銆佷笉瑙﹀彂 reportWriter/specUpdater锛汧akeModel(FAILURE) 杩斿洖 FAILURE+note 涓嶆姏
- [x] 2.2 `AgentRunner.runMandate(String id, String mandate)` + `runMandate(id, mandate, timeoutSeconds)`锛氬悎鎴愮灛鎬侀潪鑷富 spec銆佸鐢?running 閲嶅叆淇濇姢 + builder.build + execute锛沗DEFAULT_MANDATE_TIMEOUT_SECONDS` 甯搁噺
- [x] 2.3 `mvn -pl pig-agent-core -am test` 缁匡紱鍕鹃€夋湰缁?
## 3. 閰嶇疆闂?tasks.execute锛坱ask-execution锛?
- [x] 3.1 娴嬭瘯鍏堣锛歚PigAgentConfigTest`/`ConfigurationManagerTest` 鏂█ `tasks.execute` 榛樿 false銆佸彲琚?yaml 瑕嗙洊涓?true
- [x] 3.2 `PigAgentConfig` 澧?`TasksConfig`锛坄execute` 榛樿 false锛? `tasks` 瀛楁 + getter/setter
- [x] 3.3 `mvn -pl pig-agent-config -am test` 缁匡紱鍕鹃€夋湰缁?
## 4. AgentBootstrap 鎺ョ嚎锛坱ask-execution + session-snapshot-reset锛?
- [x] 4.1 娴嬭瘯鍏堣锛歚AgentBootstrapTaskExecutorTest`锛坧ig-agent-cli锛夆€斺€?`buildTaskExecutor(realAgentRunner(fakeBuilder), realTaskManager)` 寰楀埌鐨?`Consumer<Task>` 璺戜竴涓凡瀛樹换鍔″悗锛屼粨搴撲腑璇ヤ换鍔?result 闈炵┖涓斿惈 outcome 鎽樿
- [x] 4.2 `AgentBootstrap.buildTaskExecutor(AgentRunner, TaskManager)` 闈欐€佸姪鎵?+ `taskOutcomeSummary(AgentReport)`锛沗build()` 涓?`if (config.getTasks().isExecute()) taskScheduler.setTaskExecutor(buildTaskExecutor(agentRunner, taskManager))`
- [x] 4.3 `SessionManager` 鏀?7 鍙傛瀯閫狅紝浼?`compressionRef` 杞彂鐨?snapshot 閲嶇疆閽╁瓙锛沗CompressionService` 寤哄ソ鍚?`compressionRef.set(...)`
- [x] 4.4 `/tasks`锛坄ReplCommands.TasksCommand`锛夛細浠诲姟鏈?result 鏃跺垪琛ㄨ拷鍔犱竴琛屾憳瑕侊紙鎴柇锛?- [x] 4.5 `mvn -pl pig-agent-cli -am test` 缁匡紙鍓嶅彴锛屾姤鏁帮級锛涘嬀閫夋湰缁?
## 5. 鏍￠獙涓庡綊妗?
- [x] 5.1 `openspec validate task-executor-wiring --strict` 閫氳繃
- [x] 5.2 澶嶆煡鏃犲洖褰掞細榛樿 `tasks.execute=false` 鈫?浠呮彁閱掞紱8 鍙?Task 鏋勯€犱繚鐣欙紱SessionManager 6 鍙傛祴璇曚笉鍙?
