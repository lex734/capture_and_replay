Command to run the capture agent
```
java -javaagent:lib
s/trace-capture-agent.jar -cp build/test Main
```

Command to run the replay agent
```
java -javaagent:libs/trace-replay-agent.jar -cp build/test Main
```
