.SILENT:

compile:
	javac -d out --add-exports java.base/jdk.internal.vm.annotation=ALL-UNNAMED src/main/java/com/igprep/showcase/pricer/*.java

run-priceload: compile
	java --add-exports=java.base/jdk.internal.vm.annotation=ALL-UNNAMED -XX:-RestrictContended -cp out com.igprep.showcase.pricer.PriceLoadTest

run-corruptionstress: compile
	java -cp out com.igprep.showcase.pricer.CorruptionStressTest

run-gclog: compile
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