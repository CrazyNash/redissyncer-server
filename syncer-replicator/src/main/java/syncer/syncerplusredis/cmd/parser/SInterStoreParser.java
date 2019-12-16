/*
 * Copyright 2016-2018 Leon Chen
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package syncer.syncerplusredis.cmd.parser;

import syncer.syncerplusredis.cmd.CommandParser;
import syncer.syncerplusredis.cmd.impl.SInterStoreCommand;
import syncer.syncerplusredis.cmd.CommandParsers;

/**
 * @author Leon Chen
 * @since 2.1.0
 */
public class SInterStoreParser implements CommandParser<SInterStoreCommand> {
    @Override
    public SInterStoreCommand parse(Object[] command) {
        int idx = 1;
        byte[] destination = CommandParsers.toBytes(command[idx]);
        idx++;
        byte[][] keys = new byte[command.length - 2][];
        for (int i = idx, j = 0; i < command.length; i++, j++) {
            keys[j] = CommandParsers.toBytes(command[i]);
        }
        return new SInterStoreCommand(destination, keys);
    }

}