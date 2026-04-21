import 'dart:async';
import 'package:flutter/material.dart';
import '../services/zebra_service.dart';

class BarcodeScreen extends StatefulWidget {
  const BarcodeScreen({super.key});

  @override
  State<BarcodeScreen> createState() => _BarcodeScreenState();
}

class _BarcodeScreenState extends State<BarcodeScreen> {
  final _service = ZebraService.instance;
  final _scrollController = ScrollController();

  final List<String> _results = [];
  String _status = '';
  bool _scanning = false;

  StreamSubscription<ZebraEvent>? _sub;

  @override
  void initState() {
    super.initState();
    _sub = _service.events.listen(_onEvent);
  }

  void _onEvent(ZebraEvent event) {
    switch (event) {
      case BarcodeDataEvent(:final data, :final symbology, :final label):
        final sym = label.isNotEmpty ? label : '$symbology';
        _addLine('[$sym] $data');
        // Auto-release soft trigger after receiving a scan (mirrors releaseTrigger behavior)
        if (_scanning) {
          _service.stopBarcodeScan();
          setState(() => _scanning = false);
        }
      case RfidConnectedEvent(:final message):
        setState(() => _status = message);
      case RfidDisconnectedEvent():
        setState(() => _status = 'Reader disconnected');
      case MessageEvent(:final message):
        setState(() => _status = message);
      default:
        break;
    }
  }

  void _addLine(String line) {
    setState(() => _results.add(line));
    // Scroll to bottom after the frame is built (mirrors updateAndScrollDownTextView)
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_scrollController.hasClients) {
        _scrollController.animateTo(
          _scrollController.position.maxScrollExtent,
          duration: const Duration(milliseconds: 300),
          curve: Curves.easeOut,
        );
      }
    });
  }

  Future<void> _onScanPressed() async {
    if (_scanning) {
      await _service.stopBarcodeScan();
      setState(() => _scanning = false);
    } else {
      setState(() => _scanning = true);
      await _service.startBarcodeScan();
    }
  }

  void _clearResults() => setState(() => _results.clear());

  @override
  void dispose() {
    _sub?.cancel();
    _scrollController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Barcode Scanner'),
        actions: [
          IconButton(
            icon: const Icon(Icons.delete_outline),
            tooltip: 'Clear results',
            onPressed: _results.isEmpty ? null : _clearResults,
          ),
        ],
      ),
      body: Column(
        children: [
          // Status bar
          if (_status.isNotEmpty)
            Container(
              width: double.infinity,
              color: Colors.grey.shade200,
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
              child: Text(
                _status,
                style: const TextStyle(fontSize: 12),
                overflow: TextOverflow.ellipsis,
              ),
            ),

          // Scan button (soft trigger)
          Padding(
            padding: const EdgeInsets.all(12),
            child: SizedBox(
              width: double.infinity,
              child: ElevatedButton.icon(
                onPressed: _onScanPressed,
                icon: Icon(_scanning ? Icons.stop : Icons.qr_code_scanner),
                label: Text(_scanning ? 'Stop Scan' : 'Scan Barcode'),
                style: ElevatedButton.styleFrom(
                  backgroundColor: _scanning ? Colors.red.shade400 : null,
                  foregroundColor: _scanning ? Colors.white : null,
                  padding: const EdgeInsets.symmetric(vertical: 14),
                ),
              ),
            ),
          ),

          const Text(
            'Physical trigger button also activates the scanner',
            style: TextStyle(fontSize: 11, color: Colors.grey),
          ),

          const SizedBox(height: 8),
          const Divider(height: 1),

          // Results list
          Expanded(
            child: _results.isEmpty
                ? const Center(
                    child: Text(
                      'No barcodes scanned yet',
                      style: TextStyle(color: Colors.grey),
                    ),
                  )
                : ListView.builder(
                    controller: _scrollController,
                    itemCount: _results.length,
                    itemBuilder: (_, i) => ListTile(
                      dense: true,
                      leading: const Icon(Icons.qr_code, size: 18),
                      title: Text(
                        _results[i],
                        style: const TextStyle(
                          fontFamily: 'monospace',
                          fontSize: 13,
                        ),
                      ),
                    ),
                  ),
          ),
        ],
      ),
    );
  }
}
