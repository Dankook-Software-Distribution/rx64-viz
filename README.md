# xv6-viz

QEMU에서 xv6 (x86-64) 두 버전을 돌리면서 커널 자료구조를 브라우저에 그린다.
커널은 고치지 않는다. 상태는 전부 gdb로 읽는다.

## 실행

```
(cd ../rx64-riscv-style && make) && (cd ../rx64-x86-style && make)
./mvnw spring-boot:run
```

http://127.0.0.1:8080. 서버가 뜨면 두 커널이 함께 부팅된다. VM마다 `fs.img` 복사본을 쓴다.

필요한 것: JDK 25, `qemu-system-x86_64`, Python을 지원하는 gdb (12 이상).

`src/main/resources/application.properties`:

| 키 | 기본값 |
|---|---|
| `xv6viz.kernels.riscv` | `../rx64-riscv-style` |
| `xv6viz.kernels.x86` | `../rx64-x86-style` |
| `xv6viz.cpus` | `3` |

## 화면

riscv-style, x86-style, 비교 중 하나를 고른다.

| 패널 | 내용 |
|---|---|
| 셸 | xv6 콘솔 |
| `cpus[]` | mode, proc, rip, cr3, IF. 행을 고르면 소스가 그 CPU를 따라간다 |
| `proc[]` | 행을 고르면 아래 패널이 그 프로세스로 바뀐다 |
| 연결 | `cpus[i]` → `proc` → kstack, trapframe, pagetable, `struct file` → `inode` / `pipe` → `bcache.buf` |
| 스택 | kstack (x86-style은 꼭대기에 trapframe), riscv-style은 trapframe 페이지를 따로 |
| 주소 공간 | 높은 주소가 위. riscv-style은 `p->pagetable`과 `kernel_pagetable`을 나란히 |
| 페이지 테이블 | CR3부터 트리. VA를 고르면 경로를 칠한다 |
| 물리 메모리 | 128MB를 4KB 칸으로. free, kernel, pagetable, user, kstack, trapframe, pipe |
| 소스 | 실행 중인 줄 |

트랩 추적: `p->name`, `syscall`을 고르고 시작.

- x86-style: `vector64` `alltraps` `trap` `syscall` `sys_*` `trapret` `iretq` `user`
- riscv-style: `uvec64` `uservec` `usertrap` `syscall` `sys_*` `prepare_return` `userret` `iretq` `user`

단계마다 그때의 CR3(user/kernel)를 단계 이름 아래에 남긴다. `N` 키로 다음 단계.
비교 화면에서는 두 커널에 같은 명령을 보내고 같은 시스템 콜을 나란히 따라간다.

## 구조

```
브라우저 ── WebSocket /ws, REST /api ── Spring ── VmManager ─┬─ Vm riscv ─ QEMU + gdb
                                                           └─ Vm x86   ─ QEMU + gdb
gdb/xv6viz.py   -xv6-* MI 명령. 공통 코드 + Arch(X86Style, RiscvStyle)
```

- x86-style: 커널이 모든 페이지 테이블의 상위 절반에 있어 gdb 가상 주소 읽기를 쓴다.
- riscv-style: 유저 CR3에는 커널이 없어서 QEMU 물리 메모리 모드(`Qqemu.PhyMemMode`)로 읽고,
  KSTACK, TRAMPOLINE 같은 주소는 `kernel_pagetable`을 직접 따라간다.

## 한계

- 브레이크포인트로 멈추면 모든 CPU가 같이 멈춘다. 타이밍은 실제와 다르다.
- riscv-style의 `trampoline.S` 안에서는 소스 줄이 나오지 않는다 (디버그 줄 정보가 없음).
- 물리 메모리 맵은 누를 때만 읽는다 (약 0.4초).
