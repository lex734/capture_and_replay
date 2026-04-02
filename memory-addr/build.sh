#!/bin/bash
set -e  # Exit immediately if any command fails
curl -L https://repo1.maven.org/maven2/org/openjdk/jol/jol-core/0.17/jol-core-0.17.jar -o jol-core-0.17.jar   

                                                                                                                    
# Then compile and run from the test_app/ directory:                                                                     
                                                                                                                        
javac -cp .:jol-core-0.17.jar Main.java                                                                                
java -cp .:jol-core-0.17.jar --add-opens java.base/java.util=ALL-UNNAMED Main

exit 0
