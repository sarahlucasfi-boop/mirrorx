#!/usr/bin/env python3 -u
import asyncio, websockets, json, time

async def recv_json(ws, timeout=5):
    deadline = asyncio.get_event_loop().time() + timeout
    while asyncio.get_event_loop().time() < deadline:
        remaining = deadline - asyncio.get_event_loop().time()
        if remaining <= 0: break
        try:
            msg = await asyncio.wait_for(ws.recv(), timeout=remaining)
            if isinstance(msg, str): return json.loads(msg)
        except TimeoutError:
            break
    return None

async def test_touch():
    try:
        async with websockets.connect('ws://127.0.0.1:9903') as ws:
            hello = await recv_json(ws, 5)
            print(f"CONNECTED: v={hello.get('version')}, {hello.get('screen',{}).get('width')}x{hello.get('screen',{}).get('height')}")
            
            # Simulate Cursor touch mode: move, click
            for i in range(3):
                await ws.send(json.dumps({
                    "type":"touch","x":0.3+i*0.1,"y":0.4,"action":"down"
                }))
                await asyncio.sleep(0.05)
                await ws.send(json.dumps({
                    "type":"touch","x":0.35+i*0.1,"y":0.45,"action":"move"
                }))
                await asyncio.sleep(0.05)
                await ws.send(json.dumps({
                    "type":"touch","x":0.4,"y":0.5,"action":"click"
                }))
                print(f"  touch cycle {i+1} OK")
                await asyncio.sleep(0.1)
            
            # Simulate DRAW mode
            await ws.send(json.dumps({
                "type":"touch","x":0.2,"y":0.3,"action":"down"
            }))
            for j in range(5):
                await ws.send(json.dumps({
                    "type":"touch","x":0.2+j*0.05,"y":0.3+j*0.04,"action":"drag"
                }))
                await asyncio.sleep(0.02)
            await ws.send(json.dumps({
                "type":"touch","x":0.45,"y":0.5,"action":"up"
            }))
            print("  draw path OK")
            
            # Check connection still alive
            await ws.send(json.dumps({"t":"m","x":5,"y":3}))
            await asyncio.sleep(0.1)
            print("  hermes move after touch OK")
            
            print("\nALL TOUCH TESTS PASSED - server stayed connected")
    except Exception as e:
        print(f"DISCONNECTED: {e}")
        import traceback; traceback.print_exc()

asyncio.run(test_touch())
