"""
Worker Node - Distributed training and prediction server

Handles:
- TRAIN: Receive inputs/outputs, train neural network via Java module
- PREDICT: Load model and make predictions
- LIST_MODELS: List available trained models
- PUT: Legacy file upload (for compatibility)

Uses RAFT consensus for replication.
"""
import socket
import threading
import os
import argparse
import http.server
import socketserver
import json
import base64
import glob
import re
from datetime import datetime
from src.raft import RaftNode, NotLeader
import subprocess
import tempfile
import uuid

STORAGE_DIR = 'worker_storage'
MODELS_DIR = 'models'
LOG_FILE = 'worker.log'
JAVA_DIR = 'java'


def log(msg: str):
    now = datetime.utcnow().isoformat()
    line = f"{now} {msg}\n"
    print(line, end='')
    parent = os.path.dirname(LOG_FILE) or '.'
    try:
        os.makedirs(parent, exist_ok=True)
    except Exception:
        pass
    with open(LOG_FILE, 'a', encoding='utf-8') as f:
        f.write(line)


class WorkerTCPHandler(threading.Thread):
    def __init__(self, conn, addr, storage_dir, models_dir):
        super().__init__(daemon=True)
        self.conn = conn
        self.addr = addr
        self.storage_dir = storage_dir
        self.models_dir = models_dir

    def run(self):
        try:
            # Read message until newline
            data = b''
            while True:
                chunk = self.conn.recv(4096)
                if not chunk:
                    break
                data += chunk
                if b'\n' in chunk or len(data) > 1024 * 1024:
                    break

            if not data:
                return

            # Try to parse as JSON message
            try:
                msg = json.loads(data.decode('utf-8').strip())
                msg_type = msg.get('type', 'PUT')
            except json.JSONDecodeError:
                # Legacy format: assume PUT with binary data following header
                self._handle_legacy_put(data)
                return

            # Route to appropriate handler
            if msg_type == 'TRAIN':
                self._handle_train(msg)
            elif msg_type == 'PREDICT':
                self._handle_predict(msg)
            elif msg_type == 'LIST_MODELS':
                self._handle_list_models()
            else:
                self._send_response({'status': 'ERROR', 'message': f'Unknown type: {msg_type}'})

        except Exception as e:
            log(f"Handler error: {e}")
            try:
                self._send_response({'status': 'ERROR', 'message': str(e)})
            except:
                pass
        finally:
            try:
                self.conn.close()
            except:
                pass

    def _send_response(self, obj):
        self.conn.sendall((json.dumps(obj) + '\n').encode('utf-8'))

    def _handle_train(self, msg):
        """Handle TRAIN request: train a new model with provided data."""
        inputs = msg.get('inputs', [])
        outputs = msg.get('outputs', [])

        if not inputs or not outputs:
            self._send_response({'status': 'ERROR', 'message': 'Missing inputs or outputs'})
            return

        if len(inputs) != len(outputs):
            self._send_response({'status': 'ERROR', 'message': 'Inputs/outputs length mismatch'})
            return

        log(f"TRAIN request from {self.addr}: {len(inputs)} samples")

        # Check if we are leader
        try:
            # Create temporary CSV files for training
            os.makedirs(self.models_dir, exist_ok=True)
            
            train_id = str(uuid.uuid4())[:8]
            inputs_file = os.path.join(self.models_dir, f'inputs_{train_id}.csv')
            outputs_file = os.path.join(self.models_dir, f'outputs_{train_id}.csv')

            # Write CSV files
            with open(inputs_file, 'w') as f:
                for row in inputs:
                    f.write(','.join(str(x) for x in row) + '\n')

            with open(outputs_file, 'w') as f:
                for row in outputs:
                    if isinstance(row, list):
                        f.write(','.join(str(x) for x in row) + '\n')
                    else:
                        f.write(str(row) + '\n')

            log(f"Training data saved: {inputs_file}, {outputs_file}")

            # Run Java training
            model_path = os.path.join(self.models_dir, f'model_{train_id}.bin')
            model_id = self._run_java_training(inputs_file, outputs_file, model_path)

            if model_id:
                # Replicate model info via RAFT
                entry = {
                    'action': 'MODEL_TRAINED',
                    'model_id': model_id,
                    'model_path': model_path,
                    'samples': len(inputs)
                }
                
                try:
                    success = raft_node.replicate(entry)
                    if success:
                        log(f"Model {model_id} replicated successfully")
                    else:
                        log(f"Model replication failed (continuing anyway)")
                except NotLeader as nl:
                    log(f"Not leader, redirect to {nl.leader}")
                    self._send_response({'status': 'REDIRECT', 'leader': nl.leader})
                    return
                except Exception as e:
                    log(f"Replication error: {e}")

                self._send_response({'status': 'OK', 'model_id': model_id})
            else:
                self._send_response({'status': 'ERROR', 'message': 'Training failed'})

            # Cleanup temp files
            try:
                os.remove(inputs_file)
                os.remove(outputs_file)
            except:
                pass

        except NotLeader as nl:
            log(f"Not leader, redirect to {nl.leader}")
            self._send_response({'status': 'REDIRECT', 'leader': nl.leader})

    def _run_java_training(self, inputs_file, outputs_file, model_path):
        """Run Java TrainingModule and return model ID."""
        try:
            cmd = [
                'java', '-cp', JAVA_DIR, 'TrainingModule',
                'train', inputs_file, outputs_file, '1000', model_path
            ]
            log(f"Running: {' '.join(cmd)}")

            p = subprocess.Popen(
                cmd,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True
            )

            model_id = None
            for line in p.stdout:
                line = line.strip()
                log(f"JAVA: {line}")
                if line.startswith('MODEL_ID:'):
                    model_id = line.split(':', 1)[1]

            p.wait()
            log(f"Java training finished (exit {p.returncode})")

            return model_id if p.returncode == 0 else None

        except Exception as e:
            log(f"Java training error: {e}")
            return None

    def _handle_predict(self, msg):
        """Handle PREDICT request: load model and make prediction."""
        model_id = msg.get('model_id')
        input_data = msg.get('input', [])

        if not model_id:
            self._send_response({'status': 'ERROR', 'message': 'Missing model_id'})
            return

        log(f"PREDICT request from {self.addr}: model={model_id}, input={input_data}")

        # Find model file
        model_path = self._find_model(model_id)
        if not model_path:
            self._send_response({'status': 'ERROR', 'message': f'Model not found: {model_id}'})
            return

        # Run Java prediction
        try:
            input_str = ','.join(str(x) for x in input_data)
            cmd = ['java', '-cp', JAVA_DIR, 'TrainingModule', 'predict', model_path, input_str]
            log(f"Running: {' '.join(cmd)}")

            result = subprocess.run(cmd, capture_output=True, text=True, timeout=30)

            output = None
            for line in result.stdout.split('\n'):
                if line.startswith('PREDICTION:'):
                    pred_str = line.split(':', 1)[1]
                    output = [float(x) for x in pred_str.split(',')]
                    break

            if output is not None:
                self._send_response({'status': 'OK', 'output': output})
            else:
                log(f"Prediction failed: {result.stdout} {result.stderr}")
                self._send_response({'status': 'ERROR', 'message': 'Prediction failed'})

        except Exception as e:
            log(f"Predict error: {e}")
            self._send_response({'status': 'ERROR', 'message': str(e)})

    def _find_model(self, model_id):
        """Find model file by ID (partial match supported)."""
        # Look for exact match first
        for pattern in [
            os.path.join(self.models_dir, f'model_{model_id}.bin'),
            os.path.join(self.models_dir, f'*{model_id}*.bin'),
        ]:
            matches = glob.glob(pattern)
            if matches:
                return matches[0]
        return None

    def _handle_list_models(self):
        """Handle LIST_MODELS request."""
        log(f"LIST_MODELS request from {self.addr}")
        
        models = []
        pattern = os.path.join(self.models_dir, '*.bin')
        for path in glob.glob(pattern):
            fname = os.path.basename(path)
            # Extract model ID from filename
            match = re.search(r'model_(.+)\.bin', fname)
            if match:
                models.append(match.group(1))
            else:
                models.append(fname)

        self._send_response({'status': 'OK', 'models': models})

    def _handle_legacy_put(self, data):
        """Handle legacy PUT format for backward compatibility."""
        try:
            header = data.decode('utf-8', errors='ignore')
            header_obj = json.loads(header)
            fname = header_obj.get('filename')
            size = header_obj.get('size', 0)
            log(f"Legacy PUT {fname} size={size} from {self.addr}")

            received = b''
            while len(received) < size:
                chunk = self.conn.recv(min(4096, size - len(received)))
                if not chunk:
                    break
                received += chunk

            entry = {'filename': fname, 'data_b64': base64.b64encode(received).decode('ascii')}
            try:
                success = raft_node.replicate(entry)
                if success:
                    os.makedirs(self.storage_dir, exist_ok=True)
                    path = os.path.join(self.storage_dir, fname)
                    with open(path, 'wb') as f:
                        f.write(received)
                    log(f"Committed and stored {path}")
                    self._send_response({'status': 'OK'})
                else:
                    self._send_response({'status': 'FAIL'})
            except NotLeader as nl:
                self._send_response({'status': 'REDIRECT', 'leader': nl.leader})
        except Exception as e:
            log(f"Legacy PUT error: {e}")


def start_tcp_server(bind_host: str, bind_port: int, storage_dir: str, models_dir: str):
    log(f"Starting worker TCP server on {bind_host}:{bind_port}")
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.bind((bind_host, bind_port))
        s.listen()
        while True:
            conn, addr = s.accept()
            t = WorkerTCPHandler(conn, addr, storage_dir, models_dir)
            t.start()


class MonitorHandler(http.server.SimpleHTTPRequestHandler):
    def log_message(self, format, *args):
        pass  # Suppress default logging

    def do_GET(self):
        if self.path == '/logs':
            self._serve_logs()
        elif self.path == '/status':
            self._serve_status()
        elif self.path == '/models':
            self._serve_models()
        elif self.path == '/':
            self._serve_dashboard()
        else:
            self.send_error(404)

    def _serve_logs(self):
        self.send_response(200)
        self.send_header('Content-Type', 'text/plain; charset=utf-8')
        self.end_headers()
        try:
            with open(LOG_FILE, 'r', encoding='utf-8') as f:
                self.wfile.write(f.read().encode('utf-8'))
        except FileNotFoundError:
            self.wfile.write(b'No logs yet')

    def _serve_status(self):
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.end_headers()
        status = {
            'state': raft_node.state,
            'term': raft_node.current_term,
            'leader': raft_node.leader,
            'log_length': len(raft_node.log),
            'commit_index': raft_node.commit_index
        }
        self.wfile.write(json.dumps(status, indent=2).encode('utf-8'))

    def _serve_models(self):
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.end_headers()
        models = []
        pattern = os.path.join(MODELS_DIR, '*.bin')
        for path in glob.glob(pattern):
            models.append(os.path.basename(path))
        self.wfile.write(json.dumps({'models': models}, indent=2).encode('utf-8'))

    def _serve_dashboard(self):
        self.send_response(200)
        self.send_header('Content-Type', 'text/html; charset=utf-8')
        self.end_headers()
        html = f'''<!DOCTYPE html>
<html>
<head>
    <title>Worker Monitor</title>
    <style>
        body {{ font-family: monospace; background: #1a1a2e; color: #eee; padding: 20px; }}
        h1 {{ color: #00d4ff; }}
        .card {{ background: #16213e; padding: 15px; margin: 10px 0; border-radius: 8px; }}
        .label {{ color: #888; }}
        .leader {{ color: #00ff88; }}
        .follower {{ color: #ffaa00; }}
        .candidate {{ color: #ff6b6b; }}
        a {{ color: #00d4ff; }}
        pre {{ background: #0f0f23; padding: 10px; overflow-x: auto; max-height: 400px; }}
    </style>
</head>
<body>
    <h1>🖥️ Worker Monitor</h1>
    <div class="card">
        <div class="label">RAFT Status</div>
        <div id="status">Loading...</div>
    </div>
    <div class="card">
        <div class="label">Trained Models</div>
        <div id="models">Loading...</div>
    </div>
    <div class="card">
        <div class="label">Recent Logs</div>
        <pre id="logs">Loading...</pre>
    </div>
    <script>
        async function refresh() {{
            try {{
                const status = await fetch('/status').then(r => r.json());
                const stateClass = status.state;
                document.getElementById('status').innerHTML = `
                    <span class="${{stateClass}}">${{status.state.toUpperCase()}}</span> | 
                    Term: ${{status.term}} | Leader: ${{JSON.stringify(status.leader)}} |
                    Log: ${{status.log_length}} entries | Commit: ${{status.commit_index}}
                `;
            }} catch(e) {{ document.getElementById('status').textContent = 'Error'; }}

            try {{
                const models = await fetch('/models').then(r => r.json());
                document.getElementById('models').innerHTML = models.models.length 
                    ? models.models.map(m => `<div>📦 ${{m}}</div>`).join('')
                    : '<em>No models yet</em>';
            }} catch(e) {{ document.getElementById('models').textContent = 'Error'; }}

            try {{
                const logs = await fetch('/logs').then(r => r.text());
                const lines = logs.split('\\n').slice(-50).join('\\n');
                document.getElementById('logs').textContent = lines || 'No logs';
            }} catch(e) {{ document.getElementById('logs').textContent = 'Error'; }}
        }}
        refresh();
        setInterval(refresh, 3000);
    </script>
</body>
</html>'''
        self.wfile.write(html.encode('utf-8'))


def start_monitor(bind_host: str, monitor_port: int):
    log(f"Starting monitor HTTP server on {bind_host}:{monitor_port}")
    handler = MonitorHandler
    with socketserver.TCPServer((bind_host, monitor_port), handler) as httpd:
        httpd.serve_forever()


def main():
    parser = argparse.ArgumentParser(description='Worker node (socket + monitor)')
    parser.add_argument('--host', default='0.0.0.0')
    parser.add_argument('--port', type=int, default=9000)
    parser.add_argument('--monitor-port', type=int, default=8000)
    parser.add_argument('--peers', nargs='*', help='peers as host:port', default=[])
    parser.add_argument('--raft-port', type=int, default=10000, help='port for raft RPCs')
    parser.add_argument('--storage-dir', default=None, help='Directory to store files')
    parser.add_argument('--java-dir', default='java', help='Directory with Java classes')
    args = parser.parse_args()

    peers = []
    for p in args.peers:
        h, po = p.split(':')
        peers.append((h, int(po)))

    # Configure directories
    global STORAGE_DIR, MODELS_DIR, LOG_FILE, JAVA_DIR
    if args.storage_dir:
        STORAGE_DIR = args.storage_dir
    else:
        STORAGE_DIR = f'node{args.port - 9000}_storage'
    MODELS_DIR = os.path.join(STORAGE_DIR, 'models')
    LOG_FILE = os.path.join(STORAGE_DIR, f'worker.log')
    JAVA_DIR = args.java_dir

    os.makedirs(STORAGE_DIR, exist_ok=True)
    os.makedirs(MODELS_DIR, exist_ok=True)

    # Start RAFT node
    global raft_node
    raft_peers = []
    for (h, p) in peers:
        raft_peers.append((h, args.raft_port + (p - args.port)))
    raft_node = RaftNode(
        node_id=f"{args.host}:{args.port}",
        host=args.host,
        port=args.raft_port,
        peers=raft_peers,
        worker_port=args.port
    )
    raft_node.start()

    log(f"Worker started: host={args.host}, port={args.port}, raft_port={args.raft_port}")
    log(f"Storage: {STORAGE_DIR}, Models: {MODELS_DIR}")
    log(f"Peers: {peers}, RAFT peers: {raft_peers}")

    # Start monitor
    mt = threading.Thread(target=start_monitor, args=(args.host, args.monitor_port), daemon=True)
    mt.start()

    # Start TCP server (blocking)
    start_tcp_server(args.host, args.port, STORAGE_DIR, MODELS_DIR)


if __name__ == '__main__':
    main()
