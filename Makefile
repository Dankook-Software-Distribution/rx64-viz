KERNELS = os/rx64-riscv-style os/rx64-x86-style

.PHONY: help up down logs local kernels test

help:
	@echo "make up       Docker로 빌드 후 백그라운드 실행 (http://127.0.0.1:8080)"
	@echo "make down     Docker 컨테이너 종료"
	@echo "make logs     Docker 로그 보기"
	@echo "make local    커널 빌드 후 로컬에서 서버 실행 (./mvnw spring-boot:run)"
	@echo "make kernels  os/ 의 두 커널 빌드"
	@echo "make test     단위 테스트"

up:
	docker compose up -d --build

down:
	docker compose down

logs:
	docker compose logs -f

kernels:
	for k in $(KERNELS); do $(MAKE) -C $$k kernel/kernel fs.img || exit 1; done

local: kernels
	./mvnw spring-boot:run -Dspring-boot.run.arguments="--xv6viz.kernels.riscv=os/rx64-riscv-style --xv6viz.kernels.x86=os/rx64-x86-style"

test:
	./mvnw test
