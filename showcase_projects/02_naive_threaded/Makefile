run: 
	javac -d out src/main/java/com/igprep/showcase/pricer/*.java
	java -cp out com.igprep.showcase.pricer.PriceLoadTest


run-gclog:
	java -Xlog:gc*:file=gc-single.log:time,uptime,level,tags -cp out com.igprep.showcase.pricer.PriceLoadTest