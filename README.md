Command to run the capture agent
```
java -javaagent:libs/trace-capture-agent.jar -cp build/test Main
```

Command to run the replay agent
```
java -javaagent:libs/trace-replay-agent.jar -cp build/test Main
```

Command to rebuild the test script in test_app
```
javac -d build/test test_app/Main.java
```