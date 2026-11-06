# xv6-viz

QEMU에서 xv6 (x86-64) 두 버전을 돌리면서 커널 자료구조를 브라우저에 그린다.
커널은 고치지 않는다. 상태는 전부 gdb로 읽는다.

## 실행

두 커널 저장소를 `os/rx64-riscv-style`, `os/rx64-x86-style`에 둔다 (이 저장소에는 들어 있지 않다).

```
make up       # Docker로 빌드 후 백그라운드 실행. 필요한 것: Docker
make local    # os/ 커널 빌드 후 ./mvnw spring-boot:run
```

http://127.0.0.1:8080. 서버가 뜨면 두 커널이 함께 부팅된다. VM마다 `fs.img` 복사본을 쓴다.
둘 다 8080 포트를 쓰므로 `make local` 전에 `make down`.

`make local`에 필요한 것: JDK 25, `qemu-system-x86_64`, Python을 지원하는 gdb (12 이상), gcc, perl.

`src/main/resources/application.properties` (`make local`과 Docker는 커널 경로를 `os/`로 바꿔 넘긴다):

| 키 | 기본값 |
|---|---|
| `xv6viz.kernels.riscv` | `../rx64-riscv-style` |
| `xv6viz.kernels.x86` | `../rx64-x86-style` |
| `xv6viz.cpus` | `4` (1~8, 커널 NCPU) |

## 화면

위쪽에서 riscv-style, x86-style, Compare 중 하나를 고른다. 화면은 VS Code처럼 뷰로 나뉜다.

| 뷰 | 내용 |
|---|---|
| `cpus[]` | mode, proc, rip, cr3, IF. 행을 고르면 Source가 그 CPU를 따라간다 (Debug 중에는 그 CPU로 바꾼다) |
| `proc[]` | 행을 고르면 Graph, Stack, Memory가 그 프로세스로 바뀐다 |
| Graph | `cpus[i]` → `proc` → kstack, trapframe, pagetable, `struct file` → `inode` / `pipe` → `bcache.buf` |
| Stack | kstack (x86-style은 꼭대기에 trapframe), riscv-style은 trapframe 페이지를 따로 |
| Memory | Virtual(주소 공간, 높은 주소가 위) → Page table(트리, 또는 고른 VA의 4단계 walk) → Physical(128MB 지도와 확대한 32페이지). 고른 VA에서 단계마다의 표 페이지, 마지막 데이터 페이지까지 선으로 잇는다. riscv-style은 `p->pagetable`과 `kernel_pagetable`을 바꿔 본다 |
| Source | 실행 중인 줄. 파일을 골라 열 수 있고, 줄 번호를 누르면 중단점 |
| Shell | xv6 콘솔 |
| Trace | 시스템 콜 목록 (아래) |
| Call Stack, Variables, Breakpoints | Debug (아래) |

배치: 탭이나 섹션 제목을 끌어 다른 그룹·사이드바로 옮기고, 그룹 왼쪽/오른쪽 가장자리에 놓으면 그룹이 나뉜다.
`View` 메뉴에서 프리셋(`Syscall`, `Memory`, `Debug`)을 고르고 뷰를 켜고 끈다.
`Ctrl+B` 왼쪽, `Ctrl+Alt+B` 오른쪽, `Ctrl+J` 아래 영역을 켜고 끈다. 경계선을 끌어 크기를 바꾸고(더블클릭하면 기본값),
제목줄 오른쪽 버튼이나 탭 줄 더블클릭으로 최대화(`Esc`로 복귀). 배치는 브라우저에 저장된다.

### Trace

위쪽 `Trace`를 켜 두는 동안 셸에서 친 명령의 시스템 콜을 pid, 이름, 인자(문자열, 버퍼, argv, open 모드),
반환값으로 남긴다. 명령마다 `$ echo hi` 같은 머리 줄이 붙는다. 커널은 멈추지 않는다. 끄면 멈추고, `Clear`로 비운다.
시스템 콜마다 트랩 단계(레지스터, 스택, 소스, CR3)와 직후의 프로세스 상태(파일 테이블)를 같이 남긴다.

- x86-style: `vector64` `alltraps` `trap` `syscall` `sys_*` `trapret` `iretq`
- riscv-style: `uvec64` `uservec` `usertrap` `syscall` `sys_*` `prepare_return` `userret` `iretq`

줄을 고르면 Graph는 그 시스템 콜 직후 상태, Stack과 Source는 고른 단계를 보여 준다.
`→`/`N` 다음 단계(마지막이면 다음 줄), `←` 이전 단계, `↓`/`↑` 다음/이전 줄, `Esc` 실시간 화면.
Compare에서는 두 커널에서 같은 명령을 Trace로 남기고, 한쪽에서 고른 시스템 콜을 다른 쪽에서도 고른다.
`exit`의 단계는 `syscall`까지만 남는다(`sys_exit` 입구에 브레이크포인트를 걸면 게스트가 깨진다).

### Debug

커널을 실제로 멈추고 한 줄씩 실행한다. Source에서 줄 번호를 누르면 중단점(빨간 점)이고, 그 줄을 지나는
어떤 프로세스에서든 커널 전체가 멈춘다. 위쪽 `Pause`로 아무 때나 멈출 수도 있다.
멈추면 위쪽 줄에 도구가 나온다: `Continue` F5, `Step Over` F10, `Step Into` F11, `Step Out` Shift+F11 (끝나면 반환값).
Call Stack에서 프레임을 고르면 Variables와 Source가 그 함수로 바뀌고, Stack은 그 CPU가 돌리는 프로세스의 커널 스택이다.
멈춰 있는 동안 Graph, Memory도 그 순간의 실제 상태다.

- 커널은 `-O`로 빌드되어 있어 줄이 건너뛰거나 변수가 `<optimized out>`으로 나올 수 있다.
- 잠드는 함수(입력을 기다리는 `read`)를 Step Over하면 깨어날 때까지 `Running`이다. `Pause`로 끊는다.
- riscv-style은 Debug 동안 QEMU 물리 메모리 모드를 끄고 gdb 프레임으로 읽는다. step이 도는 동안에는 Trace가 쉰다.

`Follow new process`: 새 프로세스가 생기면 화면이 그 프로세스로, 끝나면 부모로 넘어간다.
바뀐 값과 새로 생긴 노드·행은 노랗게 표시한다. 필드 이름에 마우스를 올리면 C 선언 줄을 보여 준다.

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
- 물리 메모리 지도는 Memory 뷰를 처음 볼 때와 `Reload`를 누를 때만 읽는다.
