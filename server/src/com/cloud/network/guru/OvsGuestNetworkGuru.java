/**
 *  Copyright (C) 2010 Cloud.com, Inc.  All rights reserved.
 * 
 * This software is licensed under the GNU General Public License v3 or later.
 * 
 * It is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or any later version.
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * 
 */

package com.cloud.network.guru;

import javax.ejb.Local;

import org.apache.log4j.Logger;

import com.cloud.dc.DataCenter;
import com.cloud.deploy.DeployDestination;
import com.cloud.deploy.DeploymentPlan;
import com.cloud.event.EventTypes;
import com.cloud.event.EventUtils;
import com.cloud.event.EventVO;
import com.cloud.exception.InsufficientAddressCapacityException;
import com.cloud.exception.InsufficientVirtualNetworkCapcityException;
import com.cloud.network.Network;
import com.cloud.network.NetworkManager;
import com.cloud.network.NetworkVO;
import com.cloud.network.ovs.OvsNetworkManager;
import com.cloud.network.ovs.OvsTunnelManager;
import com.cloud.offering.NetworkOffering;
import com.cloud.user.Account;
import com.cloud.user.UserContext;
import com.cloud.utils.component.Inject;
import com.cloud.vm.Nic.ReservationStrategy;
import com.cloud.vm.NicProfile;
import com.cloud.vm.ReservationContext;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachineProfile;
import com.cloud.network.Networks.BroadcastDomainType;
import com.cloud.network.Network.State;

@Local(value=NetworkGuru.class)
public class OvsGuestNetworkGuru extends GuestNetworkGuru {
	private static final Logger s_logger = Logger.getLogger(OvsGuestNetworkGuru.class);
	
	@Inject OvsNetworkManager _ovsNetworkMgr;
	@Inject NetworkManager _externalNetworkManager;
	@Inject OvsTunnelManager _ovsTunnelMgr;
	
	@Override
    public Network design(NetworkOffering offering, DeploymentPlan plan, Network userSpecified, Account owner) {
      
		if (!_ovsNetworkMgr.isOvsNetworkEnabled() && !_ovsTunnelMgr.isOvsTunnelEnabled()) {
			return null;
		}
		
        NetworkVO config = (NetworkVO) super.design(offering, plan, userSpecified, owner); 
        if (config == null) {
        	return null;
        }
        
        config.setBroadcastDomainType(BroadcastDomainType.Vswitch);
        
        return config;
	}
	
    protected void allocateVnet(Network network, NetworkVO implemented, long dcId,
    		long physicalNetworkId, String reservationId) throws InsufficientVirtualNetworkCapcityException {
        if (network.getBroadcastUri() == null) {
            String vnet = _dcDao.allocateVnet(dcId, physicalNetworkId, network.getAccountId(), reservationId);
            if (vnet == null) {
                throw new InsufficientVirtualNetworkCapcityException("Unable to allocate vnet as a part of network " + network + " implement ", DataCenter.class, dcId);
            }
	   		String vnetUri = null;
	   		if (_ovsNetworkMgr.isOvsNetworkEnabled()) {
	   		    vnetUri = "vlan" + vnet;
	   		} else if (_ovsTunnelMgr.isOvsTunnelEnabled()) {
	   		    vnetUri = vnet;
	   		}
            implemented.setBroadcastUri(BroadcastDomainType.Vswitch.toUri(vnetUri));
   		 	s_logger.debug("### URI value:" + vnetUri);            
            EventUtils.saveEvent(UserContext.current().getCallerUserId(), network.getAccountId(), EventVO.LEVEL_INFO, EventTypes.EVENT_ZONE_VLAN_ASSIGN, "Assigned Zone Vlan: "+vnet+ " Network Id: "+network.getId(), 0);
        } else {
            implemented.setBroadcastUri(network.getBroadcastUri());
        }
    }
	
	@Override
	public Network implement(Network config, NetworkOffering offering, DeployDestination dest, ReservationContext context) throws InsufficientVirtualNetworkCapcityException {
		 assert (config.getState() == State.Implementing) : "Why are we implementing " + config;
		 if (!_ovsNetworkMgr.isOvsNetworkEnabled()&& !_ovsTunnelMgr.isOvsTunnelEnabled()) {
			 return null;
		 }
		 s_logger.debug("### Implementing network:" + config.getId() + " in OVS Guest Network Guru");
		 // The above call will NOT reserve a Vnet
		 NetworkVO implemented = (NetworkVO)super.implement(config, offering, dest, context);		 
         return implemented;
	}
	
}
