import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_hooks/flutter_hooks.dart';

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

class HomeScreen extends HookWidget {
  const HomeScreen({super.key});

  static const _method = MethodChannel('com.ltrudu.rfid_sample_flutter/method');
  static const _events = EventChannel('com.ltrudu.rfid_sample_flutter/events');

  @override
  Widget build(BuildContext context) {
    final rfidConnected = useState(false);
    final tags = useState<List<_TagEntry>>([]);
    final barcodeData = useState<String?>(null);
    final barcodeSymbology = useState<String?>(null);
    final log = useState<List<String>>([]);
    final power = useState<int>(30);
    final logCtrl = useScrollController();

    void appendLog(String line) {
      final t = DateTime.now();
      final ts =
          '${t.hour.toString().padLeft(2, '0')}:${t.minute.toString().padLeft(2, '0')}:${t.second.toString().padLeft(2, '0')}';
      final next = [...log.value, '$ts  $line'];
      log.value = next.length > 200 ? next.sublist(next.length - 200) : next;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (logCtrl.hasClients) {
          logCtrl.animateTo(
            logCtrl.position.maxScrollExtent,
            duration: const Duration(milliseconds: 150),
            curve: Curves.easeOut,
          );
        }
      });
    }

    useEffect(() {
      _method.invokeMethod('initialize').catchError((e) {
        appendLog('initialize error: $e');
        return null;
      });

      final sub = _events.receiveBroadcastStream().listen((raw) {
        if (raw is! Map) return;
        final type = raw['type'] as String? ?? '';
        switch (type) {
          case 'rfidConnected':
            rfidConnected.value = true;
            appendLog('RFID connected');
          case 'rfidDisconnected':
            rfidConnected.value = false;
            appendLog('RFID disconnected');
          case 'rfidError':
            appendLog('RFID error: ${raw['message'] ?? ''}');
          case 'tagData':
            final rawTags = raw['tags'];
            if (rawTags is List) {
              final updated = List<_TagEntry>.from(tags.value);
              for (final t in rawTags) {
                if (t is! Map) continue;
                final epc = (t['epc'] as String?) ?? '';
                final rssi = (t['rssi'] as String?) ?? '';
                if (epc.isEmpty) continue;
                updated.removeWhere((e) => e.epc == epc);
                updated.insert(0, _TagEntry(epc, rssi));
                if (updated.length > 50) updated.removeLast();
                appendLog('Tag: $epc  ${rssi}dBm');
              }
              tags.value = updated;
            }
          case 'barcodeData':
            barcodeData.value = raw['data'] as String?;
            barcodeSymbology.value = raw['symbology'] as String?;
            appendLog('Barcode: ${raw['data']}  [${raw['symbology']}]');
          case 'triggerPress':
            final pressed = raw['pressed'] as bool? ?? false;
            if (pressed) appendLog('Trigger pressed');
          case 'message':
            final msg = raw['message'] as String? ?? '';
            if (msg.isNotEmpty) appendLog(msg);
        }
      }, onError: (e) => appendLog('Event error: $e'));

      return sub.cancel;
    }, []);

    Future<void> invoke(String method, [Map<String, dynamic>? args]) async {
      try {
        await _method.invokeMethod(method, args);
      } catch (e) {
        appendLog('$method error: $e');
      }
    }

    return Scaffold(
      body: Column(
        children: [
          // RFID card
          Card(
            margin: const EdgeInsets.fromLTRB(8, 8, 8, 4),
            child: Padding(
              padding: const EdgeInsets.all(10),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Icon(Icons.nfc,
                          color: rfidConnected.value ? Colors.green : Colors.grey,
                          size: 20),
                      const SizedBox(width: 6),
                      Text(
                        'RFID — ${rfidConnected.value ? "Connected" : "Disconnected"}',
                        style: const TextStyle(fontWeight: FontWeight.bold),
                      ),
                      const Spacer(),
                      _Btn('Start', () => invoke('startRfidInventory')),
                      _Btn('Stop', () => invoke('stopRfidInventory')),
                      _Btn('Clear', () => tags.value = []),
                    ],
                  ),
                  const SizedBox(height: 4),
                  Row(
                    children: [
                      const Text('Power:', style: TextStyle(fontSize: 12)),
                      const SizedBox(width: 8),
                      DropdownButton<int>(
                        value: power.value,
                        isDense: true,
                        items: List.generate(26, (i) => i + 5)
                            .map((v) => DropdownMenuItem(
                                value: v, child: Text('$v dBm')))
                            .toList(),
                        onChanged: (v) {
                          if (v == null) return;
                          power.value = v;
                          invoke('setPower', {'dbm': v});
                        },
                      ),
                    ],
                  ),
                  const SizedBox(height: 4),
                  SizedBox(
                    height: 80,
                    child: tags.value.isEmpty
                        ? const Center(
                            child: Text('No tags read yet',
                                style: TextStyle(color: Colors.grey, fontSize: 12)))
                        : ListView.builder(
                            itemCount: tags.value.length,
                            itemBuilder: (_, i) => Text(
                              '${tags.value[i].epc}  ${tags.value[i].rssi} dBm',
                              style: const TextStyle(
                                  fontSize: 11, fontFamily: 'monospace'),
                            ),
                          ),
                  ),
                ],
              ),
            ),
          ),
          // Barcode card
          Card(
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
                        const Text('Barcode',
                            style: TextStyle(fontWeight: FontWeight.bold)),
                        const SizedBox(height: 2),
                        barcodeData.value != null
                            ? Text(
                                '${barcodeData.value}\n${barcodeSymbology.value}',
                                style: const TextStyle(
                                    fontSize: 12, fontFamily: 'monospace'),
                              )
                            : const Text('No scan yet',
                                style: TextStyle(color: Colors.grey, fontSize: 12)),
                      ],
                    ),
                  ),
                  _Btn('Scan', () => invoke('startBarcodeScan')),
                  _Btn('Clear', () {
                    barcodeData.value = null;
                    barcodeSymbology.value = null;
                  }),
                ],
              ),
            ),
          ),
          // Log panel
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Padding(
                  padding: const EdgeInsets.fromLTRB(12, 4, 4, 0),
                  child: Row(
                    children: [
                      const Text('Log',
                          style: TextStyle(
                              fontWeight: FontWeight.bold, fontSize: 13)),
                      const Spacer(),
                      TextButton(
                        onPressed: () => log.value = [],
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
                      controller: logCtrl,
                      padding: const EdgeInsets.all(8),
                      itemCount: log.value.length,
                      itemBuilder: (_, i) => Text(
                        log.value[i],
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

class _Btn extends StatelessWidget {
  const _Btn(this.label, this.onPressed);
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
