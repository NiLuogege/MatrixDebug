///////////////////////////////////////////////////////////////////////////////
// Copyright (c) 2002, Eric D. Friedman All Rights Reserved.
//
// This library is free software; you can redistribute it and/or
// modify it under the terms of the GNU Lesser General Public
// License as published by the Free Software Foundation; either
// version 2.1 of the License, or (at your option) any later version.
//
// This library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public
// License along with this program; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
///////////////////////////////////////////////////////////////////////////////

package com.squareup.haha.trove;

import java.io.IOException;
import java.io.ObjectOutputStream;

/**
 * Implementation of the variously typed procedure interfaces that supports
 * writing the arguments to the procedure out on an ObjectOutputStream.
 * In the case of two-argument procedures, the arguments are written out
 * in the order received.
 *
 * <p>
 * Any IOException is trapped here so that it can be rethrown in a writeObject
 * method.
 * </p>
 *
 * Created: Sun Jul  7 00:14:18 2002
 *
 * @author Eric D. Friedman
 * @version $Id: SerializationProcedure.java,v 1.6 2004/09/24 09:11:15 cdr Exp $
 */

class SerializationProcedure implements TDoubleDoubleProcedure,
        TDoubleFloatProcedure,
        TDoubleIntProcedure,
        TDoubleLongProcedure,
        TDoubleObjectProcedure,
    TDoubleProcedure,
    TFloatDoubleProcedure,
    TFloatFloatProcedure,
    TFloatIntProcedure,
    TFloatLongProcedure,
    TFloatObjectProcedure,
    TFloatProcedure,
    TIntDoubleProcedure,
    TIntFloatProcedure,
    TIntIntProcedure,
    TIntLongProcedure,
    TIntObjectProcedure,
    TIntProcedure,
    TLongDoubleProcedure,
    TLongFloatProcedure,
    TLongIntProcedure,
    TLongLongProcedure,
    TLongObjectProcedure,
    TLongProcedure,
    TObjectDoubleProcedure,
    TObjectFloatProcedure,
    TObjectIntProcedure,
    TObjectLongProcedure,
    TObjectObjectProcedure,
    TObjectProcedure {
    
    private final ObjectOutputStream stream;
    IOException exception;

    SerializationProcedure (ObjectOutputStream stream) {
        this.stream = stream;
    }

    @Override
    public boolean execute(int val) {
        try {
            stream.writeInt(val);
        } catch (IOException e) {
            exception = e;
            return false;
        }
        return true;
    }

    @Override
    public boolean execute(double val) {
        try {
            stream.writeDouble(val);
        } catch (IOException e) {
            exception = e;
            return false;
        }
        return true;
    }

    @Override
    public boolean execute(long val) {
        try {
            stream.writeLong(val);
        } catch (IOException e) {
            exception = e;
            return false;
        }
        return true;
    }

    @Override
    public boolean execute(float val) {
        try {
            stream.writeFloat(val);
        } catch (IOException e) {
            exception = e;
            return false;
        }
        return true;
    }

    @Override
    public boolean execute(Object val) {
        try {
            stream.writeObject(val);
        } catch (IOException e) {
            exception = e;
            return false;
        }
        return true;
    }

    @Override
    public boolean execute(Object key, Object val) {
        try {
            stream.writeObject(key);
            stream.writeObject(val);
        } catch (IOException e) {
            exception = e;
            return false;
        }
        return true;
    }

    @Override
    public boolean execute(Object key, int val) {
        try {
            stream.writeObject(key);
            stream.writeInt(val);
        } catch (IOException e) {
            exception = e;
            return false;
        }
        return true;
    }

    @Override
    public boolean execute(Object key, long val) {
        try {
            stream.writeObject(key);
            stream.writeLong(val);
        } catch (IOException e) {
            exception = e;
            return false;
        }
        return true;
    }

    @Override
    public boolean execute(Object key, double val) {
        try {
            stream.writeObject(key);
            stream.writeDouble(val);
        } catch (IOException e) {
            exception = e;
            return false;
        }
        return true;
    }

    @Override
    public boolean execute(Object key, float val) {
        try {
            stream.writeObject(key);
            stream.writeFloat(val);
        } catch (IOException e) {
            exception = e;
            return false;
        }
        return true;
    }

    @Override
    public boolean execute(int key, Object val) {
        try {
            stream.writeInt(key);
            stream.writeObject(val);
        } catch (IOException e) {
            exception = e;
            return false;
        }
        return true;
    }

    @Override
    public boolean execute(int key, int val) {
        try {
            stream.writeInt(key);
            stream.writeInt(val);
        } catch (IOException e) {
            exception = e;
            return false;
        }
        return true;
    }

    @Override
    public boolean execute(int key, long val) {
        try {
            stream.writeInt(key);
            stream.writeLong(val);
        } catch (IOException e) {
            exception = e;
            return false;
        }
        return true;
    }

    @Override
    public boolean execute(int key, double val) {
        try {
            stream.writeInt(key);
            stream.writeDouble(val);
        } catch (IOException e) {
            exception = e;
            return false;
        }
        return true;
    }

    @Override
    public boolean execute(int key, float val) {
        try {
            stream.writeInt(key);
            stream.writeFloat(val);
        } catch (IOException e) {
            exception = e;
            return false;
        }
        return true;
    }

    @Override
    public boolean execute(long key, Object val) {
        try {
            stream.writeLong(key);
            stream.writeObject(val);
        } catch (IOException e) {
            exception = e;
            return false;
        }
        return true;
    }

    @Override
    public boolean execute(long key, int val) {
        try {
            stream.writeLong(key);
            stream.writeInt(val);
        } catch (IOException e) {
            exception = e;
            return false;
        }
        return true;
    }

    @Override
    public boolean execute(long key, long val) {
        try {
            stream.writeLong(key);
            stream.writeLong(val);
        } catch (IOException e) {
            exception = e;
            return false;
        }
        return true;
    }

    @Override
    public boolean execute(long key, double val) {
        try {
            stream.writeLong(key);
            stream.writeDouble(val);
        } catch (IOException e) {
            exception = e;
            return false;
        }
        return true;
    }

    @Override
    public boolean execute(long key, float val) {
        try {
            stream.writeLong(key);
            stream.writeFloat(val);
        } catch (IOException e) {
            exception = e;
            return false;
        }
        return true;
    }

    @Override
    public boolean execute(double key, Object val) {
        try {
            stream.writeDouble(key);
            stream.writeObject(val);
        } catch (IOException e) {
            exception = e;
            return false;
        }
        return true;
    }

    @Override
    public boolean execute(double key, int val) {
        try {
            stream.writeDouble(key);
            stream.writeInt(val);
        } catch (IOException e) {
            exception = e;
            return false;
        }
        return true;
    }

    @Override
    public boolean execute(double key, long val) {
        try {
            stream.writeDouble(key);
            stream.writeLong(val);
        } catch (IOException e) {
            exception = e;
            return false;
        }
        return true;
    }

    @Override
    public boolean execute(double key, double val) {
        try {
            stream.writeDouble(key);
            stream.writeDouble(val);
        } catch (IOException e) {
            exception = e;
            return false;
        }
        return true;
    }

    @Override
    public boolean execute(double key, float val) {
        try {
            stream.writeDouble(key);
            stream.writeFloat(val);
        } catch (IOException e) {
            exception = e;
            return false;
        }
        return true;
    }

    @Override
    public boolean execute(float key, Object val) {
        try {
            stream.writeFloat(key);
            stream.writeObject(val);
        } catch (IOException e) {
            exception = e;
            return false;
        }
        return true;
    }

    @Override
    public boolean execute(float key, int val) {
        try {
            stream.writeFloat(key);
            stream.writeInt(val);
        } catch (IOException e) {
            exception = e;
            return false;
        }
        return true;
    }

    @Override
    public boolean execute(float key, long val) {
        try {
            stream.writeFloat(key);
            stream.writeLong(val);
        } catch (IOException e) {
            exception = e;
            return false;
        }
        return true;
    }

    @Override
    public boolean execute(float key, double val) {
        try {
            stream.writeFloat(key);
            stream.writeDouble(val);
        } catch (IOException e) {
            exception = e;
            return false;
        }
        return true;
    }

    @Override
    public boolean execute(float key, float val) {
        try {
            stream.writeFloat(key);
            stream.writeFloat(val);
        } catch (IOException e) {
            exception = e;
            return false;
        }
        return true;
    }
}// SerializationProcedure
