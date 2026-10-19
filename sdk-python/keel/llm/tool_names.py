"""Adapt platform tool identifiers at the vendor boundary without changing grants."""
import copy
import hashlib
import re


VALID_NAME = re.compile(r'[A-Za-z0-9_-]{1,64}')


def wire_tools(messages, tools):
    names = {tool.get('function', {}).get('name') for tool in (tools or [])}
    names.update(call.get('function', {}).get('name') for message in messages
                 for call in (message.get('tool_calls') or []))
    names = {name for name in names if isinstance(name, str) and name}
    used = {name for name in names if VALID_NAME.fullmatch(name)}
    encoded = {name: name for name in used}
    for name in sorted(names - used):
        stem = re.sub(r'[^A-Za-z0-9_-]', '_', name)[:40] or 'tool'
        base = stem + '_' + hashlib.sha256(name.encode()).hexdigest()[:16]
        candidate = base
        index = 0
        while candidate in used:
            index += 1
            suffix = '_' + str(index)
            candidate = base[:64 - len(suffix)] + suffix
        used.add(candidate)
        encoded[name] = candidate
    wire_messages = copy.deepcopy(messages)
    for message in wire_messages:
        for call in message.get('tool_calls') or []:
            function = call.get('function') or {}
            if function.get('name') in encoded:
                function['name'] = encoded[function['name']]
    wire_definitions = copy.deepcopy(tools)
    for tool in wire_definitions or []:
        function = tool.get('function') or {}
        if function.get('name') in encoded:
            function['name'] = encoded[function['name']]
    return wire_messages, wire_definitions, {wire: original for original, wire in encoded.items()}
