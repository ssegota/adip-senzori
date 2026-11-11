# source env.sh  — postavlja JDK 21 i Android SDK za ovaj projekt
export JAVA_HOME=$HOME/.local/jdks/21
export ANDROID_HOME=$HOME/Android/Sdk
export PATH=$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH
