define TEST_PEER_INFO
1001 localhost 6008 1
1002 localhost 6009 0
1003 localhost 6010 0
1004 localhost 6011 0
1005 localhost 6012 0
1006 localhost 6013 0
endef
export TEST_PEER_INFO

all:
	mkdir -p build
	javac -d build $$(find src -name "*.java")

run: all
	java -cp build peerProcess $(ID)

integration-test: all
	$(MAKE) save-PeerInfo
	@echo "Overwrite PeerInfo.cfg with test data"
	printf "$$TEST_PEER_INFO" > PeerInfo.cfg
	@echo "Running tests..."
	java -cp build test.IntegrationTest
	@echo "Removing peer folders."
	rm -rf peer_1002 peer_1003 peer_1004 peer_1005 peer_1006
	rm -f log_peer_1001.log log_peer_1002.log log_peer_1003.log log_peer_1004.log log_peer_1005.log log_peer_1006.log
	$(MAKE) restore-PeerInfo

unit-test: all
	java -cp build test.TestRunner
	rm -rf peer_99997 peer_99998 peer_99999

save-PeerInfo:
	@echo "Saving current PeerInfo.cfg to PeerInfo.cfg.bak"
	cp PeerInfo.cfg PeerInfo.cfg.bak

restore-PeerInfo:
	@echo "Restoring PeerInfo.cfg from PeerInfo.cfg.bak"
	cp PeerInfo.cfg.bak PeerInfo.cfg
	rm PeerInfo.cfg.bak

clean:
	rm -rf build