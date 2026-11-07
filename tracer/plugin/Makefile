# Build rxtrace.so against the QEMU plugin header.
QEMU_PLUGIN_INC ?= /opt/qemu-plugin/include
OUT ?= .
CFLAGS = -O2 -g -Wall -fPIC $(shell pkg-config --cflags glib-2.0) -I$(QEMU_PLUGIN_INC)/qemu

$(OUT)/rxtrace.so: rxtrace.c
	mkdir -p $(OUT)
	$(CC) $(CFLAGS) -shared -o $@ $< $(shell pkg-config --libs glib-2.0)
