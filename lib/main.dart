import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() => runApp(const App());

class App extends StatelessWidget {
  const App({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'TC22R Scanner',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.indigo),
        useMaterial3: true,
      ),
      home: const HomeScreen(),
    );
  }
}

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  static const _method = MethodChannel('com.ltrudu.rfid_sample_flutter/method');
  static const _events = EventChannel('com.ltrudu.rfid_sample_flutter/events');

  StreamSubscription<dynamic>? _sub;
  final ScrollController _logCtrl = ScrollController();

  bool _rfidConnected = false;
  final List<_TagEntry> _tags = [];
  String? _barcodeData;
  String? _barcodeSymbology;
  final List<String> _log = [];

  @override
  void initState() {
    super.initState();
    _sub = _events.receiveBroadcastStream().listen(_onEvent, onError: _onError);
    _init();
  }

  @override
  void dispose() {
    _sub?.cancel();
    _logCtrl.dispose();
    super.dispose();
  }

  Future<void> _init() async {
    try {
      await _method.invokeMethod('initialize');
    } catch (e) {
      _appendLog('initialize error: $e');
    }
  }

  void _onEvent(dynamic raw) {
    if (raw is! Map) return;
    final type = raw['type'] as String? ?? '';
    setState(() {
      switch (type) {
        case 'rfidConnected':
          _rfidConnected = true;
          _appendLog('RFID connected');
        case 'rfidDisconnected':
          _rfidConnected = false;
          _appendLog('RFID disconnected');
        case 'rfidError':
          _appendLog('RFID error: ${raw['message'] ?? ''}');
        case 'tagData':
          final rawTags = raw['tags'];
          if (rawTags is List) {
            for (final t in rawTags) {
              if (t is! Map) continue;
              final epc = (t['epc'] as String?) ?? '';
              final rssi = (t['rssi'] as String?) ?? '';
              if (epc.isEmpty) continue;
              _tags.removeWhere((e) => e.epc == epc);
              _tags.insert(0, _TagEntry(epc, rssi));
              if (_tags.length > 50) _tags.removeLast();
              _appendLog('Tag: $epc  ${rssi}dBm');
            }
          }
        case 'barcodeData':
          _barcodeData = raw['data'] as String?;
          _barcodeSymbology = raw['symbology'] as String?;
          _appendLog('Barcode: $_barcodeData  [$_barcodeSymbology]');
        case 'triggerPress':
          final pressed = raw['pressed'] as bool? ?? false;
          if (pressed) _appendLog('Trigger pressed');
        case 'message':
          final msg = raw['message'] as String? ?? '';
          if (msg.isNotEmpty) _appendLog(msg);
      }
    });
    _scrollLog();
  }

  void _onError(dynamic err) {
    setState(() => _appendLog('Event error: $err'));
  }

  void _appendLog(String line) {
    final t = DateTime.now();
    final ts =
        '${t.hour.toString().padLeft(2, '0')}:${t.minute.toString().padLeft(2, '0')}:${t.second.toString().padLeft(2, '0')}';
    _log.add('$ts  $line');
    if (_log.length > 200) _log.removeAt(0);
  }

  void _scrollLog() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_logCtrl.hasClients) {
        _logCtrl.animateTo(
          _logCtrl.position.maxScrollExtent,
          duration: const Duration(milliseconds: 150),
          curve: Curves.easeOut,
        );
      }
    });
  }

  Future<void> _invokeMethod(String method) async {
    try {
      await _method.invokeMethod(method);
    } catch (e) {
      setState(() => _appendLog('$method error: $e'));
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
            body: Column(
        children: [
          _RfidCard(
            connected: _rfidConnected,
            tags: _tags,
            onStart: () => _invokeMethod('startRfidInventory'),
            onStop: () => _invokeMethod('stopRfidInventory'),
            onClear: () => setState(() => _tags.clear()),
          ),
          _BarcodeCard(
            data: _barcodeData,
            symbology: _barcodeSymbology,
            onScan: () => _invokeMethod('startBarcodeScan'),
            onClear: () => setState(() {
              _barcodeData = null;
              _barcodeSymbology = null;
            }),
          ),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Padding(
                  padding: const EdgeInsets.fromLTRB(12, 6, 4, 0),
                  child: Row(
                    children: [
                      const Text('Log', style: TextStyle(fontWeight: FontWeight.bold, fontSize: 13)),
                      const Spacer(),
                      TextButton(
                        onPressed: () => setState(() => _log.clear()),
                        child: const Text('Clear'),
                      ),
                    ],
                  ),
                ),
                Expanded(
                  child: Container(
                    margin: const EdgeInsets.fromLTRB(8, 0, 8, 8),
                    decoration: BoxDecoration(
                      color: Colors.black87,
                      borderRadius: BorderRadius.circular(8),
                    ),
                    child: ListView.builder(
                      controller: _logCtrl,
                      padding: const EdgeInsets.all(8),
                      itemCount: _log.length,
                      itemBuilder: (_, i) => Text(
                        _log[i],
                        style: const TextStyle(
                          color: Colors.greenAccent,
                          fontSize: 11,
                          fontFamily: 'monospace',
                        ),
                      ),
                    ),
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _TagEntry {
  final String epc;
  final String rssi;
  _TagEntry(this.epc, this.rssi);
}

class _RfidCard extends StatelessWidget {
  const _RfidCard({
    required this.connected,
    required this.tags,
    required this.onStart,
    required this.onStop,
    required this.onClear,
  });

  final bool connected;
  final List<_TagEntry> tags;
  final VoidCallback onStart;
  final VoidCallback onStop;
  final VoidCallback onClear;

  @override
  Widget build(BuildContext context) {
    return Card(
      margin: const EdgeInsets.fromLTRB(8, 8, 8, 4),
      child: Padding(
        padding: const EdgeInsets.all(10),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(Icons.nfc, color: connected ? Colors.green : Colors.grey, size: 20),
                const SizedBox(width: 6),
                Text(
                  'RFID — ${connected ? "Connected" : "Disconnected"}',
                  style: const TextStyle(fontWeight: FontWeight.bold),
                ),
                const Spacer(),
                _SmallButton(label: 'Start', onPressed: onStart),
                _SmallButton(label: 'Stop', onPressed: onStop),
                _SmallButton(label: 'Clear', onPressed: onClear),
              ],
            ),
            const SizedBox(height: 4),
            if (tags.isEmpty)
              const Text('No tags read yet', style: TextStyle(color: Colors.grey, fontSize: 12))
            else
              SizedBox(
                height: 110,
                child: ListView.builder(
                  itemCount: tags.length,
                  itemBuilder: (_, i) => Padding(
                    padding: const EdgeInsets.symmetric(vertical: 1),
                    child: Text(
                      '${tags[i].epc}  ${tags[i].rssi} dBm',
                      style: const TextStyle(fontSize: 12, fontFamily: 'monospace'),
                    ),
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }
}

class _BarcodeCard extends StatelessWidget {
  const _BarcodeCard({
    required this.data,
    required this.symbology,
    required this.onScan,
    required this.onClear,
  });

  final String? data;
  final String? symbology;
  final VoidCallback onScan;
  final VoidCallback onClear;

  @override
  Widget build(BuildContext context) {
    return Card(
      margin: const EdgeInsets.fromLTRB(8, 4, 8, 4),
      child: Padding(
        padding: const EdgeInsets.all(10),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Icon(Icons.qr_code_scanner, size: 20),
            const SizedBox(width: 6),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text('Barcode', style: TextStyle(fontWeight: FontWeight.bold)),
                  const SizedBox(height: 2),
                  data != null
                      ? Text(
                          '$data\n$symbology',
                          style: const TextStyle(fontSize: 13, fontFamily: 'monospace'),
                        )
                      : const Text('No scan yet', style: TextStyle(color: Colors.grey, fontSize: 12)),
                ],
              ),
            ),
            _SmallButton(label: 'Scan', onPressed: onScan),
            _SmallButton(label: 'Clear', onPressed: onClear),
          ],
        ),
      ),
    );
  }
}

class _SmallButton extends StatelessWidget {
  const _SmallButton({required this.label, required this.onPressed});
  final String label;
  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) {
    return TextButton(
      style: TextButton.styleFrom(
        minimumSize: const Size(44, 32),
        padding: const EdgeInsets.symmetric(horizontal: 8),
        tapTargetSize: MaterialTapTargetSize.shrinkWrap,
      ),
      onPressed: onPressed,
      child: Text(label, style: const TextStyle(fontSize: 12)),
    );
  }
}
