"""Runner-local echo targets; only deterministic fixture text is exchanged."""
import socket
import threading

def tcp():
    with socket.socket() as server:
        server.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
        server.bind(('0.0.0.0',18080));server.listen()
        while True:
            client,_=server.accept()
            with client:
                client.settimeout(5)
                data=client.recv(4096)
                client.sendall(data)

threading.Thread(target=tcp,daemon=True).start()
with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as server:
    server.bind(('0.0.0.0',18081))
    while True:
        data,peer=server.recvfrom(4096);server.sendto(data,peer)
