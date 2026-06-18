import asyncio, websockets, json

async def recv_json(ws, timeout=5):
    """Keep reading until we get a text (JSON) frame, skip binary frames."""
    deadline = asyncio.get_event_loop().time() + timeout
    while asyncio.get_event_loop().time() < deadline:
        remaining = deadline - asyncio.get_event_loop().time()
        if remaining <= 0:
            raise TimeoutError("timeout waiting for JSON")
        try:
            msg = await asyncio.wait_for(ws.recv(), timeout=remaining)
            if isinstance(msg, str):
                return json.loads(msg)
            # else it's bytes (JPEG frame), skip
        except TimeoutError:
            raise
    raise TimeoutError("timeout waiting for JSON")

async def test():
    try:
        async with websockets.connect('ws://127.0.0.1:9902') as ws:
            hello = await recv_json(ws, 5)
            print(f"HELLO: v={hello.get('version')}, mode={hello.get('mode')}")
            
            await ws.send(json.dumps({'t':'m','x':10,'y':5}))
            print("SENT: move(10,5) OK")
            
            await ws.send(json.dumps({'t':'c','b':0}))
            ack = await recv_json(ws, 3)
            print(f"ACK click: {ack}")
            
            await ws.send(json.dumps({'type':'mirror_config','key':'quality','value':80}))
            print("SENT: mirror_config OK")
            
            print("\n=== ALL TESTS PASSED ===")
    except Exception as e:
        print(f"FAILED: {e}")
        import traceback; traceback.print_exc()

asyncio.run(test())
