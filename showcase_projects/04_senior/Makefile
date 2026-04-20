.SILENT:

run-priceload: 
	javac -d out src/main/java/com/igprep/showcase/pricer/*.java
	java -cp out com.igprep.showcase.pricer.PriceLoadTest

run-corruptionstress: 
	javac -d out src/main/java/com/igprep/showcase/pricer/*.java
	java -cp out com.igprep.showcase.pricer.CorruptionStressTest



run-gclog:
	java -Xlog:gc*:file=gc-single.log:time,uptime,level,tags -cp out com.igprep.showcase.pricer.PriceLoadTest

jstack-dump:
	@PID=$$(jps -l | grep PriceLoadTest | awk '{print $$1}'); \
	if [ -z "$$PID" ]; then \
		echo "PriceLoadTest not running. Start it first, then run this target."; \
	else \
		echo "Capturing 5 thread dumps for PID $$PID..."; \
		for i in 1 2 3 4 5; do \
			echo "--- Dump $$i ---" >> threaddump.txt; \
			jstack $$PID >> threaddump.txt; \
			sleep 2; \
		done; \
		echo "Done. See threaddump.txt"; \
	fi